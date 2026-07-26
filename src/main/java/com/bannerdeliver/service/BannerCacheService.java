package com.bannerdeliver.service;

import com.bannerdeliver.config.BannerProperties;
import com.bannerdeliver.domain.dto.BannerDateCacheKey;
import com.bannerdeliver.domain.dto.BannerDeliveryEvent;
import com.bannerdeliver.domain.dto.BannerRuntimeDTO;
import com.bannerdeliver.domain.po.BannerCrowd;
import com.bannerdeliver.domain.po.BannerInfo;
import com.bannerdeliver.utils.AudienceBucketCalculator;
import com.bannerdeliver.utils.AudienceUserListCodec;
import com.bannerdeliver.utils.BannerCacheExpiryCalculator;
import com.bannerdeliver.utils.BannerDateTimeUtils;
import com.bannerdeliver.utils.BannerRedisKeyBuilder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.cache.Cache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;

/**
 * Banner 多级缓存核心：Guava L1 + Redis L2，以及 MySQL → Redis 刷新。
 *
 * <p>核心流程：</p>
 * <ol>
 *   <li>查询：L1 未命中回源 Redis 日期 Hash</li>
 *   <li>Kafka/定时刷新：MySQL 快照 → 人群包 SADD → 日期 Hash HSET → 失效 L1</li>
 *   <li>幂等：版本 Key + updateTime 比较，重复消费安全重试</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BannerCacheService {

    private static final DateTimeFormatter WINDOW_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMddHHmm");

    private final Cache<BannerDateCacheKey, List<BannerRuntimeDTO>> localCache;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final BannerRedisKeyBuilder keyBuilder;
    private final AudienceBucketCalculator bucketCalculator;
    private final BannerCacheExpiryCalculator expiryCalculator;
    private final BannerProperties properties;
    private final AudienceUserListCodec userListCodec;
    private final BannerInfoService bannerInfoService;
    private final BannerCrowdService bannerCrowdService;

    // ---- 查询：L1 → Redis ----

    /**
     * 按商品和业务日期查询 Banner 列表。
     * L1 未命中时回源 Redis 日期 Hash，并发请求合并为一次加载。
     */
    public List<BannerRuntimeDTO> getBanners(Long productId, LocalDate date) {
        BannerDateCacheKey cacheKey = new BannerDateCacheKey(productId, date);
        try {
            return localCache.get(cacheKey, () -> List.copyOf(loadFromRedis(productId, date)));
        } catch (ExecutionException exception) {
            throw new IllegalStateException(
                    "Unable to load banner cache for " + cacheKey, exception.getCause());
        }
    }

    /** 根据分桶规则计算 bucketIndex，再执行 Redis SISMEMBER 判断用户是否命中人群包。 */
    public boolean isAudienceMember(BannerRuntimeDTO runtime, String userId) {
        if (runtime.getBucketCount() == null || runtime.getBucketCount() <= 0) {
            return false;
        }
        int bucketIndex = bucketCalculator.bucketIndex(userId, runtime.getBucketCount());
        String audienceKey = keyBuilder.audienceBucketKey(
                runtime.getBannerId(), runtime.getAudienceBatch(), bucketIndex);
        return Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(audienceKey, userId));
    }

    // ---- 刷新：MySQL → Redis → 失效 L1 ----

    /** Kafka 消费入口：以 eventId 作为 audienceBatch 从 MySQL 刷新 Redis。 */
    public RefreshResult refreshFromMysql(BannerDeliveryEvent event) {
        return refreshFromMysql(event.getBannerId(), event.getEventId(), false);
    }

    /** 定时对账入口：强制刷新，跳过版本 Key 的快速跳过逻辑。 */
    public RefreshResult repairFromMysql(Long bannerId, String audienceBatch) {
        return refreshFromMysql(bannerId, audienceBatch, true);
    }

    /**
     * 统一的 MySQL → Redis 刷新流程。
     * 顺序：读快照 → 写人群包 → 切换日期 Hash → 失效 L1。
     */
    private RefreshResult refreshFromMysql(
            Long bannerId, String audienceBatch, boolean forceRefresh) {
        BannerSnapshot snapshot = loadSnapshot(bannerId);
        BannerInfo banner = snapshot.banner();
        if (banner == null) {
            invalidateLocalCache(deleteBanner(bannerId));
            return RefreshResult.DELETED;
        }
        BannerDateTimeUtils.parseDateTime(banner.getUpdateTime(), "updateTime");
        if (!forceRefresh && hasSameOrNewerVersion(banner.getBannerId(), banner.getUpdateTime())) {
            return RefreshResult.SKIPPED_SAME_OR_OLDER_VERSION;
        }

        List<LocalDate> dates = BannerDateTimeUtils.inclusiveDates(
                banner.getBeginTime(), banner.getEndTime());
        Map<String, Instant> dateKeys = new LinkedHashMap<>();
        for (LocalDate date : dates) {
            dateKeys.put(
                    keyBuilder.dateCacheKey(banner.getProductId(), date),
                    expiryCalculator.dateCacheExpireAt(date));
        }
        Instant audienceExpiry = dateKeys.values().stream()
                .max(Instant::compareTo)
                .orElseThrow();
        int bucketCount = writeAudienceVersion(
                banner.getBannerId(), audienceBatch, snapshot.crowds(), audienceExpiry);

        BannerRuntimeDTO runtime = BannerRuntimeDTO.builder()
                .bannerId(banner.getBannerId())
                .productId(banner.getProductId())
                .url(banner.getUrl())
                .beginTime(banner.getBeginTime())
                .endTime(banner.getEndTime())
                .status(banner.getStatus())
                .bucketCount(bucketCount)
                .audienceBatch(audienceBatch)
                .updateTime(banner.getUpdateTime())
                .build();
        invalidateLocalCache(replaceBannerRuntime(runtime, dateKeys));
        log.info(
                "Refreshed banner cache: bannerId={}, batch={}, bucketCount={}, dates={}",
                banner.getBannerId(), audienceBatch, bucketCount, dates.size());
        return RefreshResult.REFRESHED;
    }

    // ---- 对账辅助 ----

    /** 查询对账时间窗口内 update_time 发生变更的 Banner 列表。 */
    @Transactional(readOnly = true)
    public List<BannerInfo> findUpdatedBetween(
            LocalDateTime windowStart, LocalDateTime windowEnd) {
        return bannerInfoService.findUpdatedBetween(windowStart, windowEnd);
    }

    /** 检查 Redis 中每个应覆盖日期的 Runtime 是否都存在且 updateTime 不早于 MySQL。 */
    public boolean hasLatestRuntime(BannerInfo dbBanner) {
        LocalDateTime dbUpdateTime = BannerDateTimeUtils.parseDateTime(
                dbBanner.getUpdateTime(), "updateTime");
        for (LocalDate date : BannerDateTimeUtils.inclusiveDates(
                dbBanner.getBeginTime(), dbBanner.getEndTime())) {
            Optional<BannerRuntimeDTO> runtime = findBannerRuntime(
                    dbBanner.getProductId(), date, dbBanner.getBannerId());
            if (runtime.isEmpty() || runtime.get().getUpdateTime() == null) {
                return false;
            }
            try {
                LocalDateTime redisUpdateTime = BannerDateTimeUtils.parseDateTime(
                        runtime.get().getUpdateTime(), "redis.updateTime");
                if (redisUpdateTime.isBefore(dbUpdateTime)) {
                    return false;
                }
            } catch (IllegalArgumentException exception) {
                return false;
            }
        }
        return true;
    }

    /** Kafka 消费成功后，将 bannerId 写入对应时间窗口的 Redis Set。 */
    public void recordConsumeSuccess(Long bannerId, String eventTime) {
        LocalDateTime eventDateTime = BannerDateTimeUtils.parseDateTime(eventTime, "eventTime");
        int windowMinutes = properties.getReconciliation().getWindowMinutes();
        int minute = eventDateTime.getMinute() / windowMinutes * windowMinutes;
        LocalDateTime windowStart = eventDateTime.withMinute(minute).withSecond(0).withNano(0);
        String consumeKey = keyBuilder.consumeWindowKey(WINDOW_FORMATTER.format(windowStart));
        redisTemplate.opsForSet().add(consumeKey, String.valueOf(bannerId));
        redisTemplate.expireAt(
                consumeKey,
                Date.from(windowStart.plusDays(2)
                        .atZone(properties.getCache().getRedis().getZoneId())
                        .toInstant()));
    }

    /** 读取指定对账窗口内 Kafka 已成功消费的 bannerId 集合。 */
    public Set<Long> findConsumedBannerIds(String window) {
        Set<String> members = redisTemplate.opsForSet().members(
                keyBuilder.consumeWindowKey(window));
        if (members == null || members.isEmpty()) {
            return Set.of();
        }
        Set<Long> bannerIds = new HashSet<>();
        for (String member : members) {
            try {
                bannerIds.add(Long.valueOf(member));
            } catch (NumberFormatException ignored) {
                // 忽略脏数据，避免中断对账。
            }
        }
        return Set.copyOf(bannerIds);
    }

    // ---- 内部实现 ----

    /** 从 MySQL 读取 Banner 配置及其人群包分页，组成一致快照。 */
    @Transactional(readOnly = true)
    BannerSnapshot loadSnapshot(Long bannerId) {
        BannerInfo banner = bannerInfoService.findById(bannerId);
        if (banner == null) {
            return new BannerSnapshot(null, List.of());
        }
        return new BannerSnapshot(banner, bannerCrowdService.findByBannerId(bannerId));
    }

    /** 直接从 Redis 日期 Hash 加载并反序列化全部 Banner Runtime。 */
    private List<BannerRuntimeDTO> loadFromRedis(Long productId, LocalDate date) {
        String dateKey = keyBuilder.dateCacheKey(productId, date);
        return redisTemplate.opsForHash().values(dateKey).stream()
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .map(this::deserialize)
                .toList();
    }

    /** 比较 Redis 版本 Key，相同或更新则跳过重复 Kafka 消息的刷新。 */
    private boolean hasSameOrNewerVersion(Long bannerId, String updateTime) {
        String cached = redisTemplate.opsForValue().get(keyBuilder.versionKey(bannerId));
        return cached != null && cached.compareTo(updateTime) >= 0;
    }

    /** 从 Redis 日期 Hash 读取单个 Banner 的 Runtime JSON。 */
    private Optional<BannerRuntimeDTO> findBannerRuntime(
            Long productId, LocalDate date, Long bannerId) {
        Object runtimeJson = redisTemplate.opsForHash().get(
                keyBuilder.dateCacheKey(productId, date), String.valueOf(bannerId));
        if (runtimeJson == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(deserialize(String.valueOf(runtimeJson)));
        } catch (IllegalStateException exception) {
            return Optional.empty();
        }
    }

    /** 解码人群包、分桶并批量 SADD 到新的 audienceBatch，返回 bucketCount。 */
    private int writeAudienceVersion(
            Long bannerId,
            String audienceBatch,
            List<BannerCrowd> crowds,
            Instant baseExpireAt) {
        long userCount = crowds.stream()
                .map(BannerCrowd::getUserList)
                .map(userListCodec::decode)
                .mapToLong(List::size)
                .sum();
        int bucketCount = bucketCalculator.bucketCount(
                userCount, properties.getAudience().getTargetBucketSize());
        if (bucketCount == 0) {
            return 0;
        }
        int writeBatchSize = properties.getAudience().getRedisWriteBatchSize();
        Map<Integer, List<String>> buffers = new HashMap<>();
        for (BannerCrowd crowd : crowds) {
            for (String userId : userListCodec.decode(crowd.getUserList())) {
                int bucketIndex = bucketCalculator.bucketIndex(userId, bucketCount);
                List<String> buffer = buffers.computeIfAbsent(
                        bucketIndex, ignored -> new ArrayList<>(writeBatchSize));
                buffer.add(userId);
                if (buffer.size() >= writeBatchSize) {
                    flushAudienceMembers(bannerId, audienceBatch, bucketIndex, buffer, baseExpireAt);
                }
            }
        }
        buffers.forEach((bucketIndex, buffer) ->
                flushAudienceMembers(bannerId, audienceBatch, bucketIndex, buffer, baseExpireAt));
        return bucketCount;
    }

    /** 将缓冲区中的 userId 批量写入指定人群桶 Set 并设置 TTL。 */
    private void flushAudienceMembers(
            Long bannerId,
            String audienceBatch,
            int bucketIndex,
            List<String> userIds,
            Instant baseExpireAt) {
        if (userIds.isEmpty()) {
            return;
        }
        String audienceKey = keyBuilder.audienceBucketKey(bannerId, audienceBatch, bucketIndex);
        redisTemplate.opsForSet().add(audienceKey, userIds.toArray(String[]::new));
        redisTemplate.expireAt(
                audienceKey,
                Date.from(expiryCalculator.withStableJitter(baseExpireAt, audienceKey)));
        userIds.clear();
    }

    /** 切换 Banner 日期 Hash 并更新版本/索引，返回需失效的 L1 Key 集合。 */
    private Set<BannerDateCacheKey> replaceBannerRuntime(
            BannerRuntimeDTO runtime, Map<String, Instant> dateKeysAndExpiry) {
        String bannerField = String.valueOf(runtime.getBannerId());
        String runtimeJson = serialize(runtime);
        String updateTime = runtime.getUpdateTime();
        String dateIndexKey = keyBuilder.dateKeyIndex(runtime.getBannerId());
        Set<String> oldDateKeys = redisTemplate.opsForSet().members(dateIndexKey);
        Set<BannerDateCacheKey> affected = new HashSet<>();

        dateKeysAndExpiry.forEach((dateKey, expireAt) -> {
            keyBuilder.parseDateCacheKey(dateKey).ifPresent(affected::add);
            upsertRuntimeField(dateKey, bannerField, updateTime, runtimeJson, expireAt);
        });

        if (oldDateKeys != null) {
            oldDateKeys.stream()
                    .filter(oldKey -> !dateKeysAndExpiry.containsKey(oldKey))
                    .forEach(oldKey -> {
                        keyBuilder.parseDateCacheKey(oldKey).ifPresent(affected::add);
                        deleteRuntimeFieldIfNotNewer(oldKey, bannerField, updateTime);
                    });
        }

        if (!dateKeysAndExpiry.isEmpty()) {
            Instant maxExpireAt = dateKeysAndExpiry.values().stream()
                    .max(Instant::compareTo)
                    .orElseThrow();
            updateMetadata(
                    runtime.getBannerId(),
                    updateTime,
                    dateKeysAndExpiry.keySet(),
                    expiryCalculator.withStableJitter(maxExpireAt, dateIndexKey));
        }
        return Set.copyOf(affected);
    }

    /** 向日期 Hash 写入 Runtime 字段，旧 updateTime 不会被覆盖。 */
    private void upsertRuntimeField(
            String dateKey,
            String bannerField,
            String updateTime,
            String runtimeJson,
            Instant expireAt) {
        if (isHashFieldNewer(dateKey, bannerField, updateTime)) {
            return;
        }
        redisTemplate.opsForHash().put(dateKey, bannerField, runtimeJson);
        redisTemplate.expireAt(dateKey, Date.from(expireAt));
    }

    /** 删除过期日期 Hash 中的 Banner 字段，较新版本不会被旧任务删除。 */
    private void deleteRuntimeFieldIfNotNewer(
            String dateKey, String bannerField, String updateTime) {
        if (isHashFieldNewer(dateKey, bannerField, updateTime)) {
            return;
        }
        redisTemplate.opsForHash().delete(dateKey, bannerField);
    }

    /** 判断 Hash 中现有 Runtime 的 updateTime 是否严格新于待写入版本。 */
    private boolean isHashFieldNewer(String dateKey, String bannerField, String updateTime) {
        Object current = redisTemplate.opsForHash().get(dateKey, bannerField);
        if (current == null) {
            return false;
        }
        try {
            BannerRuntimeDTO existing = deserialize(String.valueOf(current));
            return existing.getUpdateTime() != null
                    && existing.getUpdateTime().compareTo(updateTime) > 0;
        } catch (IllegalStateException exception) {
            return false;
        }
    }

    /** 更新 banner:version 和 banner:date-keys 索引，旧版本不能覆盖新版本。 */
    private void updateMetadata(
            Long bannerId,
            String updateTime,
            Collection<String> dateKeys,
            Instant indexExpireAt) {
        String versionKey = keyBuilder.versionKey(bannerId);
        String cachedVersion = redisTemplate.opsForValue().get(versionKey);
        if (cachedVersion != null && cachedVersion.compareTo(updateTime) > 0) {
            return;
        }
        String dateIndexKey = keyBuilder.dateKeyIndex(bannerId);
        redisTemplate.delete(dateIndexKey);
        if (!dateKeys.isEmpty()) {
            redisTemplate.opsForSet().add(dateIndexKey, dateKeys.toArray(String[]::new));
        }
        redisTemplate.opsForValue().set(versionKey, updateTime);
        Date expireAt = Date.from(indexExpireAt);
        redisTemplate.expireAt(versionKey, expireAt);
        redisTemplate.expireAt(dateIndexKey, expireAt);
    }

    /** 幂等删除 Banner 的全部日期 Hash 字段、版本 Key 和日期索引。 */
    private Set<BannerDateCacheKey> deleteBanner(Long bannerId) {
        String bannerField = String.valueOf(bannerId);
        String dateIndexKey = keyBuilder.dateKeyIndex(bannerId);
        Set<String> dateKeys = redisTemplate.opsForSet().members(dateIndexKey);
        Set<BannerDateCacheKey> affected = new HashSet<>();
        if (dateKeys != null) {
            dateKeys.stream()
                    .map(keyBuilder::parseDateCacheKey)
                    .flatMap(Optional::stream)
                    .forEach(affected::add);
            dateKeys.forEach(dateKey ->
                    redisTemplate.opsForHash().delete(dateKey, bannerField));
        }
        redisTemplate.delete(List.of(dateIndexKey, keyBuilder.versionKey(bannerId)));
        return Set.copyOf(affected);
    }

    /** 批量失效 Guava L1 中受 Redis 刷新影响的 productId + date Key。 */
    private void invalidateLocalCache(Collection<BannerDateCacheKey> cacheKeys) {
        if (!cacheKeys.isEmpty()) {
            localCache.invalidateAll(cacheKeys);
        }
    }

    /** 将 BannerRuntimeDTO 序列化为 JSON 字符串写入 Redis。 */
    private String serialize(BannerRuntimeDTO runtime) {
        try {
            return objectMapper.writeValueAsString(runtime);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize BannerRuntimeDTO", exception);
        }
    }

    /** 将 Redis 中的 JSON 字符串反序列化为 BannerRuntimeDTO。 */
    private BannerRuntimeDTO deserialize(String runtimeJson) {
        try {
            return objectMapper.readValue(runtimeJson, BannerRuntimeDTO.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid BannerRuntimeJson in Redis", exception);
        }
    }

    /** MySQL → Redis 刷新的结果状态。 */
    public enum RefreshResult {
        /** 已成功刷新 Redis 缓存。 */
        REFRESHED,
        /** Banner 已从 MySQL 删除，Redis 缓存已清理。 */
        DELETED,
        /** Redis 版本相同或更新，跳过重复刷新。 */
        SKIPPED_SAME_OR_OLDER_VERSION
    }

    /** Banner 配置与人群包的一致 MySQL 快照。 */
    record BannerSnapshot(BannerInfo banner, List<BannerCrowd> crowds) {
    }
}
