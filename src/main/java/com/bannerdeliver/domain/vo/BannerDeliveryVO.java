package com.bannerdeliver.domain.vo;

import com.bannerdeliver.domain.dto.BannerDeliveryResult;
import com.bannerdeliver.domain.dto.BannerDeliverySource;

public record BannerDeliveryVO(
        Long bannerId,
        Long productId,
        String url,
        String beginTime,
        String endTime,
        BannerDeliverySource source,
        String cacheDate) {

    public static BannerDeliveryVO from(BannerDeliveryResult result) {
        return new BannerDeliveryVO(
                result.banner().getBannerId(),
                result.banner().getProductId(),
                result.banner().getUrl(),
                result.banner().getBeginTime(),
                result.banner().getEndTime(),
                result.source(),
                result.cacheDate() == null ? null : result.cacheDate().toString());
    }
}
