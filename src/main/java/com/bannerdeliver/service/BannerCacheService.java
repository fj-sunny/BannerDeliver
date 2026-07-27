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
 * 查询走 L1→Redis；刷新走 先删旧位置→写人群包→写日期 Hash→失效 L1；
 * 幂等靠 Hash 内 updateTime 比较 + 人群包 SADD，不使用 version Key。
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

    /** L1 未命中回源 Redis；并发未命中由 Guava Cache.get 合并为一次加载。 */
    public List<BannerRuntimeDTO> getBanners(Long productId, LocalDate date) {
        BannerDateCacheKey cacheKey = new BannerDateCacheKey(productId, date);
        try {
            return localCache.get(cacheKey, () -> List.copyOf(loadFromRedis(productId, date)));
        } catch (ExecutionException exception) {
            throw new IllegalStateException(
                    "Unable to load banner cache for " + cacheKey, exception.getCause());
        }
    }

    /**
     * 判断 userId 是否属于该 Banner 的人群包。
     * bucketIndex 由 userId.hashCode % bucketCount 计算；audienceBatch 用于拼 Redis Key 批次。
     */
    public boolean isAudienceMember(BannerRuntimeDTO runtime, String userId) {
        if (runtime.getBucketCount() == null || runtime.getBucketCount() <= 0) {
            return false;
        }
        int bucketIndex = bucketCalculator.bucketIndex(userId, runtime.getBucketCount());
        String audienceKey = keyBuilder.audienceBucketKey(
                runtime.getBannerId(), runtime.getAudienceBatch(), bucketIndex);
        return Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(audienceKey, userId));
    }

    /** Kafka 消费入口：先按事件 old 投放范围 HDEL，再写新数据；eventId 作 audienceBatch。 */
    public RefreshResult refreshFromMysql(BannerDeliveryEvent event) {
        return refreshFromMysql(event.getBannerId(), event.getEventId(), placementFromEvent(event));
    }

    /** 定时对账补偿：按当前 MySQL 投放范围先删后写。 */
    public RefreshResult repairFromMysql(Long bannerId, String audienceBatch) {
        BannerSnapshot snapshot = loadSnapshot(bannerId);
        BannerInfo banner = snapshot.banner();
        PlacementCleanup cleanup = banner == null ? null
                : new PlacementCleanup(banner.getProductId(), banner.getBeginTime(), banner.getEndTime());
        return refreshFromMysql(bannerId, audienceBatch, cleanup);
    }

    /** 统一刷新流程：删旧位置→读快照→写人群包→写日期 Hash→失效 L1。 */
    private RefreshResult refreshFromMysql(
            Long bannerId, String audienceBatch, PlacementCleanup cleanup) {
        Set<BannerDateCacheKey> affected = new HashSet<>();
        if (cleanup != null) {
            affected.addAll(deleteRuntimeFromPlacement(
                    bannerId, cleanup.productId(), cleanup.beginTime(), cleanup.endTime()));
        }
        BannerSnapshot snapshot = loadSnapshot(bannerId);
        BannerInfo banner = snapshot.banner();
        if (banner == null) {
            invalidateLocalCache(affected);
            return RefreshResult.DELETED;
        }
        BannerDateTimeUtils.parseDateTime(banner.getUpdateTime(), "updateTime");
        List<LocalDate> dates = BannerDateTimeUtils.inclusiveDates(
                banner.getBeginTime(), banner.getEndTime());
        Map<String, Instant> dateKeys = new LinkedHashMap<>();
        for (LocalDate date : dates) {
            dateKeys.put(
                    keyBuilder.dateCacheKey(banner.getProductId(), date),
                    expiryCalculator.dateCacheExpireAt(date));
        }
        Instant audienceExpiry = dateKeys.values().stream().max(Instant::compareTo).orElseThrow();
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
        affected.addAll(replaceBannerRuntime(runtime, dateKeys));
        invalidateLocalCache(affected);
        log.info("Refreshed banner cache: bannerId={}, batch={}, bucketCount={}, dates={}",
                banner.getBannerId(), audienceBatch, bucketCount, dates.size());
        return RefreshResult.REFRESHED;
    }

    /** 查询对账窗口 [windowStart, windowEnd) 内 update_time 变更的 Banner。 */
    @Transactional(readOnly = true)
    public List<BannerInfo> findUpdatedBetween(
            LocalDateTime windowStart, LocalDateTime windowEnd) {
        return bannerInfoService.findUpdatedBetween(windowStart, windowEnd);
    }

    /** 检查每个应覆盖日期的 Redis Runtime 是否存在且 updateTime 不早于 MySQL。 */
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

    /** Kafka 消费成功后写入 mq:consume:{window}，供对账使用。 */
    public void recordConsumeSuccess(Long bannerId, String eventTime) {
        LocalDateTime eventDateTime = BannerDateTimeUtils.parseDateTime(eventTime, "eventTime");
        int windowMinutes = properties.getReconciliation().getWindowMinutes();
        int minute = eventDateTime.getMinute() / windowMinutes * windowMinutes;
        LocalDateTime windowStart = eventDateTime.withMinute(minute).withSecond(0).withNano(0);
        String consumeKey = keyBuilder.consumeWindowKey(WINDOW_FORMATTER.format(windowStart));
        redisTemplate.opsForSet().add(consumeKey, String.valueOf(bannerId));
        redisTemplate.expireAt(consumeKey, Date.from(windowStart.plusDays(2)
                .atZone(properties.getCache().getRedis().getZoneId())
                .toInstant()));
    }

    /** 读取指定对账窗口内已成功消费的 bannerId 集合。 */
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
                // 忽略脏数据
            }
        }
        return Set.copyOf(bannerIds);
    }

    /** 从 MySQL 读取 Banner 配置及人群包，组成一致快照。 */
    @Transactional(readOnly = true)
    BannerSnapshot loadSnapshot(Long bannerId) {
        BannerInfo banner = bannerInfoService.findById(bannerId);
        if (banner == null) {
            return new BannerSnapshot(null, List.of());
        }
        return new BannerSnapshot(banner, bannerCrowdService.findByBannerId(bannerId));
    }

    /** 从 Kafka 事件提取变更前投放范围；old* 不完整时跳过清理（如对账路径）。 */
    private PlacementCleanup placementFromEvent(BannerDeliveryEvent event) {
        if (event.getOldProductId() == null
                || event.getOldBeginTime() == null
                || event.getOldEndTime() == null) {
            return null;
        }
        return new PlacementCleanup(
                event.getOldProductId(), event.getOldBeginTime(), event.getOldEndTime());
    }

    /** 在指定商品+日期范围内，从各日期 Hash 中 HDEL 该 bannerId 的 Runtime 字段。 */
    private Set<BannerDateCacheKey> deleteRuntimeFromPlacement(
            Long bannerId, Long productId, String beginTime, String endTime) {
        String bannerField = String.valueOf(bannerId);
        Set<BannerDateCacheKey> affected = new HashSet<>();
        for (LocalDate date : BannerDateTimeUtils.inclusiveDates(beginTime, endTime)) {
            String dateKey = keyBuilder.dateCacheKey(productId, date);
            keyBuilder.parseDateCacheKey(dateKey).ifPresent(affected::add);
            redisTemplate.opsForHash().delete(dateKey, bannerField);
        }
        return Set.copyOf(affected);
    }

    /** 从 Redis 日期 Hash 读取全部 Banner Runtime 并反序列化。 */
    private List<BannerRuntimeDTO> loadFromRedis(Long productId, LocalDate date) {
        String dateKey = keyBuilder.dateCacheKey(productId, date);
        return redisTemplate.opsForHash().values(dateKey).stream()
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .map(this::deserialize)
                .toList();
    }

    /** 读取单个 Banner 在指定商品+日期的 Runtime，不存在或 JSON 非法时返回 empty。 */
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

    /** 将人群包用户按 bucketIndex 分桶写入 Redis Set，返回 bucketCount。 */
    private int writeAudienceVersion(
            Long bannerId, String audienceBatch, List<BannerCrowd> crowds, Instant baseExpireAt) {
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

    /** 批量 SADD 一个桶内的 userId 并设置带抖动的 TTL。 */
    private void flushAudienceMembers(
            Long bannerId, String audienceBatch, int bucketIndex,
            List<String> userIds, Instant baseExpireAt) {
        if (userIds.isEmpty()) {
            return;
        }
        String audienceKey = keyBuilder.audienceBucketKey(bannerId, audienceBatch, bucketIndex);
        redisTemplate.opsForSet().add(audienceKey, userIds.toArray(String[]::new));
        redisTemplate.expireAt(audienceKey,
                Date.from(expiryCalculator.withStableJitter(baseExpireAt, audienceKey)));
        userIds.clear();
    }

    /** 将 Runtime JSON 写入各日期 Hash，返回受影响的 L1 Key 集合。 */
    private Set<BannerDateCacheKey> replaceBannerRuntime(
            BannerRuntimeDTO runtime, Map<String, Instant> dateKeysAndExpiry) {
        String bannerField = String.valueOf(runtime.getBannerId());
        String runtimeJson = serialize(runtime);
        String updateTime = runtime.getUpdateTime();
        Set<BannerDateCacheKey> affected = new HashSet<>();
        dateKeysAndExpiry.forEach((dateKey, expireAt) -> {
            keyBuilder.parseDateCacheKey(dateKey).ifPresent(affected::add);
            upsertRuntimeField(dateKey, bannerField, updateTime, runtimeJson, expireAt);
        });
        return Set.copyOf(affected);
    }

    /** HSET 单个 Hash 字段；若已有更新版本则跳过，防止旧消息覆盖新数据。 */
    private void upsertRuntimeField(
            String dateKey, String bannerField, String updateTime,
            String runtimeJson, Instant expireAt) {
        if (isHashFieldNewer(dateKey, bannerField, updateTime)) {
            return;
        }
        redisTemplate.opsForHash().put(dateKey, bannerField, runtimeJson);
        redisTemplate.expireAt(dateKey, Date.from(expireAt));
    }

    /** 判断 Hash 中现有 Runtime 的 updateTime 是否严格晚于待写入值。 */
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

    /** 批量失效 Guava L1 中受影响的 productId+date 条目。 */
    private void invalidateLocalCache(Collection<BannerDateCacheKey> cacheKeys) {
        if (!cacheKeys.isEmpty()) {
            localCache.invalidateAll(cacheKeys);
        }
    }

    /** 将 Runtime 序列化为 JSON 字符串写入 Redis。 */
    private String serialize(BannerRuntimeDTO runtime) {
        try {
            return objectMapper.writeValueAsString(runtime);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize BannerRuntimeDTO", exception);
        }
    }

    /** 将 Redis 中的 JSON 反序列化为 Runtime，格式非法时抛 IllegalStateException。 */
    private BannerRuntimeDTO deserialize(String runtimeJson) {
        try {
            return objectMapper.readValue(runtimeJson, BannerRuntimeDTO.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid BannerRuntimeJson in Redis", exception);
        }
    }

    /** 缓存刷新结果：已写入新数据，或 Banner 已不存在仅做清理。 */
    public enum RefreshResult {
        /** MySQL 有数据且 Redis 已刷新。 */
        REFRESHED,
        /** MySQL 无此 Banner，仅清理旧 Redis 并失效 L1。 */
        DELETED
    }

    /** 变更前投放位置：商品 ID + 起止时间，用于 HDEL 旧日期 Hash。 */
    private record PlacementCleanup(Long productId, String beginTime, String endTime) {
    }

    /** MySQL 一致快照：Banner 配置 + 全部分页人群包。 */
    record BannerSnapshot(BannerInfo banner, List<BannerCrowd> crowds) {
    }
}
