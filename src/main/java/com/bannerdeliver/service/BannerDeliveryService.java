package com.bannerdeliver.service;

import com.bannerdeliver.config.BannerProperties;
import com.bannerdeliver.domain.dto.BannerDeliveryResult;
import com.bannerdeliver.domain.dto.BannerDeliverySource;
import com.bannerdeliver.domain.dto.BannerRuntimeDTO;
import com.bannerdeliver.utils.BannerTimeUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 用户 Banner 投放查询。
 *
 * <pre>
 * 对缓存中的候选 Banner 按 bannerId 升序逐个判断：
 *   1. status == 1
 *   2. beginTime/endTime 覆盖「当前时刻」
 *   3. userId 属于该 Banner 人群包
 * 当天无命中 → 读历史业务日缓存，仍按上面 1→2→3 判断（时间仍比「现在」）
 * 仍无命中 → 静态默认 Banner
 * </pre>
 */
@Service
@RequiredArgsConstructor
public class BannerDeliveryService {

    private final BannerCacheService cacheService;
    private final BannerProperties properties;

    /** 投放查询入口。 */
    public BannerDeliveryResult query(Long productId, String userId) {
        long now = System.currentTimeMillis();
        long zoneOffset = properties.getCache().getRedis().getZoneOffsetMillis();
        long today = BannerTimeUtils.startOfDay(now, zoneOffset);

        // 当天：status → 当前时间范围 → 人群包
        Optional<BannerRuntimeDTO> matched = findFirstMatch(productId, userId, now, today);
        if (matched.isPresent()) {
            return new BannerDeliveryResult(matched.get(), BannerDeliverySource.TODAY, today);
        }

        // 兜底 1：历史业务日缓存（时间条件仍用「现在」）
        long fallbackDays = properties.getCache().getRedis().getFallbackDays();
        if (fallbackDays > 0) {
            long previousDate = today - fallbackDays * BannerTimeUtils.DAY_MILLIS;
            matched = findFirstMatch(productId, userId, now, previousDate);
            if (matched.isPresent()) {
                return new BannerDeliveryResult(
                        matched.get(), BannerDeliverySource.PREVIOUS_DATE, previousDate);
            }
        }

        // 兜底 2：静态默认
        BannerProperties.DefaultBanner defaults = properties.getDelivery().getDefaultBanner();
        BannerRuntimeDTO fallback = BannerRuntimeDTO.builder()
                .bannerId(defaults.getBannerId())
                .productId(productId)
                .url(defaults.getUrl())
                .status(1)
                .bucketCount(0)
                .audienceBatch("static-default")
                .build();
        return new BannerDeliveryResult(fallback, BannerDeliverySource.STATIC_DEFAULT, null);
    }

    /**
     * 从指定业务日缓存选第一个命中 Banner。
     * 判断顺序固定：status → 当前时间是否在投放期 → 人群包；命中多个时取最小 bannerId。
     */
    private Optional<BannerRuntimeDTO> findFirstMatch(
            Long productId, String userId, long now, long cacheDate) {
        List<BannerRuntimeDTO> candidates = cacheService.getBanners(productId, cacheDate).stream()
                .sorted(Comparator.comparing(BannerRuntimeDTO::getBannerId))
                .toList();

        for (BannerRuntimeDTO runtime : candidates) {
            // 1. 必须启用
            if (!Integer.valueOf(1).equals(runtime.getStatus())) {
                continue;
            }
            // 2. 当前时刻必须落在 [beginTime, endTime]
            if (runtime.getBeginTime() == null
                    || runtime.getEndTime() == null
                    || now < runtime.getBeginTime()
                    || now > runtime.getEndTime()) {
                continue;
            }
            // 3. 用户必须在人群包内
            if (!cacheService.isAudienceMember(runtime, userId)) {
                continue;
            }
            return Optional.of(runtime);
        }
        return Optional.empty();
    }
}
