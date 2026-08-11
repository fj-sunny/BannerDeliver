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
 * <p>核心：当天 LocalCache → Redis → 状态/时间/人群匹配。
 * 兜底：历史日期、静态默认 Banner（补充）。</p>
 */
@Service
@RequiredArgsConstructor
public class BannerDeliveryService {

    private final BannerCacheService cacheService;
    private final BannerProperties properties;

    /**
     * 用户 Banner 投放查询入口。
     * 优先当天命中，未命中则走历史日期或静态默认兜底。
     */
    public BannerDeliveryResult query(Long productId, String userId) {
        Long queryTime = System.currentTimeMillis();
        Long queryDate = BannerTimeUtils.startOfDay(
                queryTime, properties.getCache().getRedis().getZoneOffsetMillis());

        Optional<BannerRuntimeDTO> today = findFirstEligible(
                productId, userId, queryTime, queryDate);
        if (today.isPresent()) {
            return new BannerDeliveryResult(
                    today.get(),
                    BannerDeliverySource.TODAY,
                    queryDate);
        }
        return resolveFallback(productId, userId, queryTime);
    }

    /** 返回指定业务日期内所有符合状态、投放时间和人群条件的 Banner。 */
    public List<BannerRuntimeDTO> findEligibleBanners(
            Long productId, String userId, Long queryTime) {
        Long queryDate = BannerTimeUtils.startOfDay(
                queryTime, properties.getCache().getRedis().getZoneOffsetMillis());
        return eligibleCandidates(productId, queryTime, queryDate)
                .filter(runtime -> cacheService.isAudienceMember(runtime, userId))
                .toList();
    }

    /** 返回首个符合条件的 Banner，按 bannerId 升序取第一个。 */
    private Optional<BannerRuntimeDTO> findFirstEligible(
            Long productId, String userId, Long queryTime, Long cacheDate) {
        return eligibleCandidates(productId, queryTime, cacheDate)
                .filter(runtime -> cacheService.isAudienceMember(runtime, userId))
                .findFirst();
    }

    /** 从缓存加载候选 Banner 并按 status、投放时间过滤排序。 */
    private java.util.stream.Stream<BannerRuntimeDTO> eligibleCandidates(
            Long productId, Long queryTime, Long cacheDate) {
        return cacheService.getBanners(productId, cacheDate).stream()
                .filter(runtime -> Integer.valueOf(1).equals(runtime.getStatus()))
                .filter(runtime -> isWithinDeliveryTime(runtime, queryTime))
                .sorted(Comparator.comparing(BannerRuntimeDTO::getBannerId));
    }

    /** 当天未命中时，尝试历史日期兜底或返回静态默认 Banner。 */
    private BannerDeliveryResult resolveFallback(
            Long productId, String userId, Long queryTime) {
        Long fallbackDays = properties.getCache().getRedis().getFallbackDays();
        if (fallbackDays > 0) {
            Long fallbackQueryTime = queryTime - fallbackDays * BannerTimeUtils.DAY_MILLIS;
            Long fallbackDate = BannerTimeUtils.startOfDay(
                    fallbackQueryTime,
                    properties.getCache().getRedis().getZoneOffsetMillis());
            Optional<BannerRuntimeDTO> fallbackBanner = findFirstEligible(
                    productId, userId, fallbackQueryTime, fallbackDate);
            if (fallbackBanner.isPresent()) {
                return new BannerDeliveryResult(
                        fallbackBanner.get(),
                        BannerDeliverySource.PREVIOUS_DATE,
                        fallbackDate);
            }
        }

        BannerProperties.DefaultBanner defaultBanner =
                properties.getDelivery().getDefaultBanner();
        BannerRuntimeDTO runtime = BannerRuntimeDTO.builder()
                .bannerId(defaultBanner.getBannerId())
                .productId(productId)
                .url(defaultBanner.getUrl())
                .status(1)
                .bucketCount(0)
                .audienceBatch("static-default")
                .build();
        return new BannerDeliveryResult(
                runtime, BannerDeliverySource.STATIC_DEFAULT, null);
    }

    /** 判断 queryTime 是否落在 Banner 的 beginTime 与 endTime 之间（含边界）。 */
    private boolean isWithinDeliveryTime(BannerRuntimeDTO runtime, Long queryTime) {
        return runtime.getBeginTime() != null
                && runtime.getEndTime() != null
                && queryTime >= runtime.getBeginTime()
                && queryTime <= runtime.getEndTime();
    }

}
