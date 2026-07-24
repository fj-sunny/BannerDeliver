package com.bannerdeliver.cache.redis;

import com.bannerdeliver.cache.BannerRuntimeCache;
import com.bannerdeliver.config.BannerProperties;
import com.bannerdeliver.domain.dto.BannerDateCacheKey;
import com.bannerdeliver.domain.dto.BannerRuntimeDTO;
import com.bannerdeliver.domain.po.BannerInfo;
import com.bannerdeliver.utils.AudienceBucketCalculator;
import com.bannerdeliver.utils.BannerCacheExpiryCalculator;
import com.bannerdeliver.utils.BannerDateTimeUtils;
import com.bannerdeliver.utils.BannerRedisKeyBuilder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Banner Redis 数据访问实现。
 *
 * <p>负责日期 Hash、人群包 Set、版本 Key、日期索引及 MQ 消费窗口。这里不做业务决策，
 * 只封装 Redis Key、序列化、Lua 版本栅栏和固定绝对过期时间。</p>
 */
@Repository
@RequiredArgsConstructor
public class BannerRedisRepository implements BannerRuntimeCache {

    private static final DateTimeFormatter WINDOW_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMddHHmm");
    private static final DefaultRedisScript<Long> UPSERT_RUNTIME_SCRIPT =
            new DefaultRedisScript<>("""
                    local current = redis.call('HGET', KEYS[1], ARGV[1])
                    if current then
                        local ok, value = pcall(cjson.decode, current)
                        if ok and value['updateTime'] and value['updateTime'] > ARGV[2] then
                            return 0
                        end
                    end
                    redis.call('HSET', KEYS[1], ARGV[1], ARGV[3])
                    redis.call('EXPIREAT', KEYS[1], ARGV[4])
                    return 1
                    """, Long.class);
    private static final DefaultRedisScript<Long> DELETE_RUNTIME_IF_NOT_NEWER_SCRIPT =
            new DefaultRedisScript<>("""
                    local current = redis.call('HGET', KEYS[1], ARGV[1])
                    if not current then
                        return 1
                    end
                    local ok, value = pcall(cjson.decode, current)
                    if ok and value['updateTime'] and value['updateTime'] > ARGV[2] then
                        return 0
                    end
                    redis.call('HDEL', KEYS[1], ARGV[1])
                    return 1
                    """, Long.class);
    private static final DefaultRedisScript<Long> UPDATE_METADATA_SCRIPT =
            new DefaultRedisScript<>("""
                    local currentVersion = redis.call('GET', KEYS[1])
                    if currentVersion and currentVersion > ARGV[1] then
                        return 0
                    end
                    redis.call('DEL', KEYS[2])
                    for i = 3, #ARGV do
                        redis.call('SADD', KEYS[2], ARGV[i])
                    end
                    redis.call('SET', KEYS[1], ARGV[1])
                    redis.call('EXPIREAT', KEYS[1], ARGV[2])
                    redis.call('EXPIREAT', KEYS[2], ARGV[2])
                    return 1
                    """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final BannerRedisKeyBuilder keyBuilder;
    private final AudienceBucketCalculator bucketCalculator;
    private final BannerCacheExpiryCalculator expiryCalculator;
    private final BannerProperties properties;

    /** Kafka 重复消息快速判断：Redis 版本相同或更新时无需再次构建缓存。 */
    public boolean hasSameOrNewerVersion(Long bannerId, String updateTime) {
        String cachedUpdateTime = redisTemplate.opsForValue().get(keyBuilder.versionKey(bannerId));
        return cachedUpdateTime != null && cachedUpdateTime.compareTo(updateTime) >= 0;
    }

    /**
     * 所有应存在的日期 Hash 都包含当前 Banner，且 updateTime 不早于 MySQL，才视为最新。
     */
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

    public Optional<BannerRuntimeDTO> findBannerRuntime(
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

    /** 按批次向人群包 Set 写用户；SADD 去重，稳定抖动保证重试不会延长 TTL。 */
    public void addAudienceMembers(String audienceKey, Collection<String> userIds,
                                   Instant baseExpireAt) {
        if (userIds.isEmpty()) {
            return;
        }
        redisTemplate.opsForSet().add(audienceKey, userIds.toArray(String[]::new));
        redisTemplate.expireAt(
                audienceKey,
                Date.from(expiryCalculator.withStableJitter(baseExpireAt, audienceKey)));
    }

    /**
     * 切换 Banner Runtime，并返回需要失效的本地缓存 Key。
     *
     * <p>Lua 会比较 updateTime，防止较旧的 Kafka/定时任务覆盖新版本。</p>
     */
    public Set<BannerDateCacheKey> replaceBannerRuntime(
            BannerRuntimeDTO runtime, Map<String, Instant> dateKeysAndExpiry) {
        String bannerField = String.valueOf(runtime.getBannerId());
        String runtimeJson = serialize(runtime);
        String dateIndexKey = keyBuilder.dateKeyIndex(runtime.getBannerId());
        Set<String> oldDateKeys = redisTemplate.opsForSet().members(dateIndexKey);
        Set<BannerDateCacheKey> affectedCacheKeys = new HashSet<>();
        dateKeysAndExpiry.keySet().stream()
                .map(keyBuilder::parseDateCacheKey)
                .flatMap(Optional::stream)
                .forEach(affectedCacheKeys::add);

        dateKeysAndExpiry.forEach((dateKey, expireAt) -> {
            // 单 Key Lua 将 updateTime 比较、HSET 和固定 EXPIREAT 合并为原子操作。
            redisTemplate.execute(
                    UPSERT_RUNTIME_SCRIPT,
                    List.of(dateKey),
                    bannerField,
                    runtime.getUpdateTime(),
                    runtimeJson,
                    String.valueOf(expireAt.getEpochSecond()));
        });

        if (oldDateKeys != null) {
            oldDateKeys.stream()
                    .map(keyBuilder::parseDateCacheKey)
                    .flatMap(Optional::stream)
                    .forEach(affectedCacheKeys::add);
            oldDateKeys.stream()
                    .filter(oldKey -> !dateKeysAndExpiry.containsKey(oldKey))
                    .forEach(oldKey -> redisTemplate.execute(
                            DELETE_RUNTIME_IF_NOT_NEWER_SCRIPT,
                            List.of(oldKey),
                            bannerField,
                            runtime.getUpdateTime()));
        }

        if (!dateKeysAndExpiry.isEmpty()) {
            Instant maxExpireAt = dateKeysAndExpiry.values().stream()
                    .max(Instant::compareTo)
                    .orElseThrow();
            Instant indexExpireAt = expiryCalculator.withStableJitter(maxExpireAt, dateIndexKey);
            String versionKey = keyBuilder.versionKey(runtime.getBannerId());
            List<String> metadataArguments = new ArrayList<>();
            metadataArguments.add(runtime.getUpdateTime());
            metadataArguments.add(String.valueOf(indexExpireAt.getEpochSecond()));
            metadataArguments.addAll(dateKeysAndExpiry.keySet());
            // version 与日期索引共用 {bannerId} hash tag，可在 Redis Cluster 中原子更新。
            redisTemplate.execute(
                    UPDATE_METADATA_SCRIPT,
                    List.of(versionKey, dateIndexKey),
                    metadataArguments.toArray());
        }
        return Set.copyOf(affectedCacheKeys);
    }

    /** 幂等删除 Banner Runtime、日期索引和版本 Key。 */
    public Set<BannerDateCacheKey> deleteBanner(Long bannerId) {
        String bannerField = String.valueOf(bannerId);
        String dateIndexKey = keyBuilder.dateKeyIndex(bannerId);
        Set<String> dateKeys = redisTemplate.opsForSet().members(dateIndexKey);
        Set<BannerDateCacheKey> affectedCacheKeys = new HashSet<>();
        if (dateKeys != null) {
            dateKeys.stream()
                    .map(keyBuilder::parseDateCacheKey)
                    .flatMap(Optional::stream)
                    .forEach(affectedCacheKeys::add);
            dateKeys.forEach(dateKey ->
                    redisTemplate.opsForHash().delete(dateKey, bannerField));
        }
        redisTemplate.delete(List.of(dateIndexKey, keyBuilder.versionKey(bannerId)));
        return Set.copyOf(affectedCacheKeys);
    }

    /** 从 product + date Hash 读取并反序列化全部 Banner Runtime。 */
    @Override
    public List<BannerRuntimeDTO> findBanners(Long productId, LocalDate date) {
        String dateKey = keyBuilder.dateCacheKey(productId, date);
        return redisTemplate.opsForHash().values(dateKey).stream()
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .map(this::deserialize)
                .toList();
    }

    /** 根据 bucketCount 和 userId 定位唯一人群包 Set，再执行 SISMEMBER。 */
    @Override
    public boolean isAudienceMember(BannerRuntimeDTO runtime, String userId) {
        if (runtime.getBucketCount() == null || runtime.getBucketCount() <= 0) {
            return false;
        }
        int bucketIndex = bucketCalculator.bucketIndex(userId, runtime.getBucketCount());
        String audienceKey = keyBuilder.audienceBucketKey(
                runtime.getBannerId(), runtime.getAudienceBatch(), bucketIndex);
        return Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(audienceKey, userId));
    }

    /** Redis 业务刷新成功后，记录该事件所属五分钟窗口。 */
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
                // 忽略非本系统写入的异常 member，避免单个脏值中断整次对账。
            }
        }
        return Set.copyOf(bannerIds);
    }

    private String serialize(BannerRuntimeDTO runtime) {
        try {
            return objectMapper.writeValueAsString(runtime);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize BannerRuntimeDTO", exception);
        }
    }

    private BannerRuntimeDTO deserialize(String runtimeJson) {
        try {
            return objectMapper.readValue(runtimeJson, BannerRuntimeDTO.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid BannerRuntimeJson in Redis", exception);
        }
    }
}
