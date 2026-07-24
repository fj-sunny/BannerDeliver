package com.bannerdeliver.service;

import com.bannerdeliver.cache.local.BannerLocalCache;
import com.bannerdeliver.cache.redis.AudienceCacheWriter;
import com.bannerdeliver.cache.redis.BannerRedisRepository;
import com.bannerdeliver.domain.dto.BannerDeliveryEvent;
import com.bannerdeliver.domain.dto.BannerRuntimeDTO;
import com.bannerdeliver.domain.po.BannerInfo;
import com.bannerdeliver.utils.BannerCacheExpiryCalculator;
import com.bannerdeliver.utils.BannerDateTimeUtils;
import com.bannerdeliver.utils.BannerRedisKeyBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 根据 MySQL 最新快照重建 Banner 运行时缓存。
 *
 * <p>Kafka Consumer 与定时修复共用此服务，确保两条入口遵循完全相同的顺序：
 * 写完新版人群包、切换日期 Hash、失效本机 LocalCache。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BannerCacheRefreshService {

    private final BannerSnapshotService snapshotService;
    private final AudienceCacheWriter audienceCacheWriter;
    private final BannerRedisRepository redisRepository;
    private final BannerLocalCache localCache;
    private final BannerRedisKeyBuilder keyBuilder;
    private final BannerCacheExpiryCalculator expiryCalculator;

    /**
     * Kafka 消息只提供定位信息；实际业务状态始终重新读取 MySQL。
     */
    public RefreshResult refreshFromMysql(BannerDeliveryEvent event) {
        return refreshFromMysql(event.getBannerId(), event.getEventId(), false);
    }

    /**
     * 定时对账已经确认 Redis 缺失或落后，因此强制按 MySQL 快照完整重建缓存。
     */
    public RefreshResult repairFromMysql(Long bannerId, String audienceBatch) {
        return refreshFromMysql(bannerId, audienceBatch, true);
    }

    private RefreshResult refreshFromMysql(
            Long bannerId, String audienceBatch, boolean forceRefresh) {
        BannerSnapshotService.BannerSnapshot snapshot =
                snapshotService.load(bannerId);
        BannerInfo banner = snapshot.banner();
        if (banner == null) {
            localCache.invalidateAll(redisRepository.deleteBanner(bannerId));
            return RefreshResult.DELETED;
        }
        // updateTime 是 Redis 版本栅栏，写入前必须保证固定格式可按字典序比较。
        BannerDateTimeUtils.parseDateTime(banner.getUpdateTime(), "updateTime");

        if (!forceRefresh && redisRepository.hasSameOrNewerVersion(
                banner.getBannerId(), banner.getUpdateTime())) {
            return RefreshResult.SKIPPED_SAME_OR_OLDER_VERSION;
        }

        List<LocalDate> dates = BannerDateTimeUtils.inclusiveDates(
                banner.getBeginTime(), banner.getEndTime());
        Map<String, Instant> dateKeysAndExpiry = new LinkedHashMap<>();
        for (LocalDate date : dates) {
            dateKeysAndExpiry.put(
                    keyBuilder.dateCacheKey(banner.getProductId(), date),
                    expiryCalculator.dateCacheExpireAt(date));
        }
        Instant audienceBaseExpireAt = dateKeysAndExpiry.values().stream()
                .max(Instant::compareTo)
                .orElseThrow();

        // eventId 作为不可变的新版本号；全部 Set 写完后才切换日期 Hash 中的 JSON。
        int bucketCount = audienceCacheWriter.writeAudienceVersion(
                banner.getBannerId(), audienceBatch, snapshot.crowds(),
                audienceBaseExpireAt);
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
        localCache.invalidateAll(
                redisRepository.replaceBannerRuntime(runtime, dateKeysAndExpiry));
        log.info("Refreshed banner cache: bannerId={}, eventId={}, bucketCount={}, dates={}",
                banner.getBannerId(), audienceBatch, bucketCount, dates.size());
        return RefreshResult.REFRESHED;
    }

    public enum RefreshResult {
        REFRESHED,
        DELETED,
        SKIPPED_SAME_OR_OLDER_VERSION
    }
}
