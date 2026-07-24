package com.bannerdeliver.service;

import com.bannerdeliver.config.BannerProperties;
import com.bannerdeliver.domain.dto.BannerDeliveryResult;
import com.bannerdeliver.domain.dto.BannerDeliverySource;
import com.bannerdeliver.domain.dto.BannerRuntimeDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 对外 Banner 投放查询业务。
 *
 * <p>依次尝试当天缓存、历史日期缓存和静态默认 Banner，并标记最终数据来源。</p>
 */
@Service
@RequiredArgsConstructor
public class BannerDeliveryService {

    private final BannerDeliveryQueryService queryService;
    private final BannerProperties properties;
    private final Clock bannerClock;

    public BannerDeliveryResult query(Long productId, String userId) {
        LocalDateTime queryTime = LocalDateTime.now(bannerClock);
        Optional<BannerRuntimeDTO> today = queryService.findFirstEligibleBanner(
                productId, userId, queryTime);
        if (today.isPresent()) {
            return new BannerDeliveryResult(
                    today.get(), BannerDeliverySource.TODAY, queryTime.toLocalDate());
        }

        int fallbackDays = properties.getCache().getRedis().getFallbackDays();
        if (fallbackDays > 0) {
            // 使用历史日期相同的时分秒判断 beginTime/endTime，否则昨日 Banner 必然已过期。
            LocalDateTime fallbackQueryTime = queryTime.minusDays(fallbackDays);
            Optional<BannerRuntimeDTO> fallback = queryService.findFirstEligibleBanner(
                    productId, userId, fallbackQueryTime);
            if (fallback.isPresent()) {
                return new BannerDeliveryResult(
                        fallback.get(),
                        BannerDeliverySource.PREVIOUS_DATE,
                        fallbackQueryTime.toLocalDate());
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
}
