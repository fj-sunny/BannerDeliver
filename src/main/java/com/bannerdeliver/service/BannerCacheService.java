package com.bannerdeliver.service;

import com.bannerdeliver.config.BannerProperties;
import com.bannerdeliver.domain.dto.BannerDateCacheKey;
import com.bannerdeliver.domain.dto.BannerEventType;
import com.bannerdeliver.domain.dto.BannerRuntimeDTO;
import com.bannerdeliver.domain.po.BannerCrowd;
import com.bannerdeliver.domain.po.BannerInfo;
import com.bannerdeliver.utils.AudienceBucketCalculator;
import com.bannerdeliver.utils.AudienceUserListCodec;
import com.bannerdeliver.utils.BannerCacheExpiryCalculator;
import com.bannerdeliver.utils.BannerTimeUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.cache.Cache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Banner 多级缓存核心：查询走 Guava L1 → Redis L2，变更后从 MySQL 全量刷新两级缓存。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BannerCacheService {

    private final Cache<BannerDateCacheKey, List<BannerRuntimeDTO>> localCache;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final AudienceBucketCalculator bucketCalculator;
    private final BannerCacheExpiryCalculator expiryCalculator;
    private final BannerProperties properties;
    private final AudienceUserListCodec userListCodec;
    private final BannerInfoService bannerInfoService;
    private final BannerCrowdService bannerCrowdService;

    /** L1 未命中回源 Redis；并发未命中由 Guava Cache.get 合并为一次加载。 */
    public List<BannerRuntimeDTO> getBanners(Long productId, Long date) {
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
        String audienceKey = "banner:audience:%s:%s:bucket:%s".formatted(
                runtime.getBannerId(), runtime.getAudienceBatch(), bucketIndex);
        return Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(audienceKey, userId));
    }

    /**
     * 从 MySQL 刷新一个 Banner 的 Redis 与本地缓存。
     * BANNER_UPDATE 更新日期 Hash；AUDIENCE_UPDATE 和 FULL_UPDATE 同时更新人群 Set。
     */
    @Transactional(readOnly = true)
    public void refreshBannerCache(
            Long bannerId, String eventId, BannerEventType eventType) {
        // 1. 根据 bannerId 从 MySQL 读取最新 Banner。
        BannerInfo banner = bannerInfoService.findById(bannerId);
        if (banner == null) {
            return;
        }

        // 2. 根据最新投放范围确定需要新增或覆盖的 BannerInfo 日期 Hash。
        Set<BannerDateCacheKey> affected = new HashSet<>();
        List<Long> dates = BannerTimeUtils.inclusiveDates(
                banner.getBeginTime(),
                banner.getEndTime(),
                properties.getCache().getRedis().getZoneOffsetMillis());
        Map<String, Long> dateKeys = new LinkedHashMap<>();
        for (Long date : dates) {
            dateKeys.put(
                    "product:%s:date:%s".formatted(banner.getProductId(), date),
                    expiryCalculator.dateCacheExpireAt(date));
            affected.add(new BannerDateCacheKey(banner.getProductId(), date));
        }
        Long audienceExpiry = dateKeys.values().stream().max(Long::compareTo).orElseThrow();

        String audienceBatch = null;
        Integer existingBucketCount = null;
        if (eventType == BannerEventType.BANNER_UPDATE) {
            for (String dateKey : dateKeys.keySet()) {
                Object current = redisTemplate.opsForHash().get(
                        dateKey, String.valueOf(bannerId));
                if (current != null) {
                    BannerRuntimeDTO runtime = deserialize(String.valueOf(current));
                    audienceBatch = runtime.getAudienceBatch();
                    existingBucketCount = runtime.getBucketCount();
                    break;
                }
            }
        }
        boolean refreshAudience = eventType != BannerEventType.BANNER_UPDATE
                || audienceBatch == null
                || existingBucketCount == null;
        if (refreshAudience) {
            audienceBatch = eventId;
        }
        int bucketCount = refreshAudience
                ? writeAudienceVersion(
                        bannerId,
                        audienceBatch,
                        bannerCrowdService.findByBannerId(bannerId),
                        audienceExpiry)
                : existingBucketCount;
        if (!refreshAudience) {
            for (int bucketIndex = 0; bucketIndex < bucketCount; bucketIndex++) {
                String audienceKey =
                        "banner:audience:%s:%s:bucket:%s".formatted(
                                bannerId, audienceBatch, bucketIndex);
                expireAt(
                        audienceKey,
                        expiryCalculator.withStableJitter(audienceExpiry, audienceKey));
            }
        }
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
        String bannerField = String.valueOf(bannerId);
        String runtimeJson = serialize(runtime);
        dateKeys.forEach((dateKey, expireAt) -> {
            redisTemplate.opsForHash().put(dateKey, bannerField, runtimeJson);
            expireAt(dateKey, expireAt);
        });

        // 3. Redis 同步完成后，立即覆盖本机缓存。
        affected.forEach(key -> localCache.put(
                key, List.copyOf(loadFromRedis(key.productId(), key.date()))));

        log.info("Refreshed banner cache: bannerId={}, batch={}, bucketCount={}, dates={}",
                banner.getBannerId(), audienceBatch, bucketCount, dates.size());
    }

    /** 查询对账窗口 [windowStart, windowEnd) 内 update_time 变更的 Banner。 */
    @Transactional(readOnly = true)
    public List<BannerInfo> findUpdatedBetween(
            Long windowStart, Long windowEnd) {
        return bannerInfoService.findUpdatedBetween(windowStart, windowEnd);
    }

    /** 检查每个应覆盖日期的 Redis Runtime 是否存在且 updateTime 不早于 MySQL。 */
    public boolean hasLatestRuntime(BannerInfo dbBanner) {
        for (Long date : BannerTimeUtils.inclusiveDates(
                dbBanner.getBeginTime(),
                dbBanner.getEndTime(),
                properties.getCache().getRedis().getZoneOffsetMillis())) {
            Optional<BannerRuntimeDTO> runtime = findBannerRuntime(
                    dbBanner.getProductId(), date, dbBanner.getBannerId());
            if (runtime.isEmpty() || runtime.get().getUpdateTime() == null) {
                return false;
            }
            if (runtime.get().getUpdateTime() < dbBanner.getUpdateTime()) {
                return false;
            }
        }
        return true;
    }

    /** 从 Redis 日期 Hash 读取全部 Banner Runtime 并反序列化。 */
    private List<BannerRuntimeDTO> loadFromRedis(Long productId, Long date) {
        String dateKey = "product:%s:date:%s".formatted(productId, date);
        return redisTemplate.opsForHash().values(dateKey).stream()
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .map(this::deserialize)
                .toList();
    }

    /** 读取单个 Banner 在指定商品+日期的 Runtime，不存在或 JSON 非法时返回 empty。 */
    private Optional<BannerRuntimeDTO> findBannerRuntime(
            Long productId, Long date, Long bannerId) {
        Object runtimeJson = redisTemplate.opsForHash().get(
                "product:%s:date:%s".formatted(productId, date),
                String.valueOf(bannerId));
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
            Long bannerId, String audienceBatch, List<BannerCrowd> crowds, Long baseExpireAt) {
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
            List<String> userIds, Long baseExpireAt) {
        if (userIds.isEmpty()) {
            return;
        }
        String audienceKey = "banner:audience:%s:%s:bucket:%s".formatted(
                bannerId, audienceBatch, bucketIndex);
        redisTemplate.opsForSet().add(audienceKey, userIds.toArray(String[]::new));
        expireAt(audienceKey, expiryCalculator.withStableJitter(baseExpireAt, audienceKey));
        userIds.clear();
    }

    private void expireAt(String key, Long expireAt) {
        redisTemplate.expire(
                key,
                Math.max(0L, expireAt - System.currentTimeMillis()),
                TimeUnit.MILLISECONDS);
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

}
