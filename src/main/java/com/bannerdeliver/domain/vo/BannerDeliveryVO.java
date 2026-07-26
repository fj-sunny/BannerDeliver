package com.bannerdeliver.domain.vo;

import com.bannerdeliver.domain.dto.BannerDeliveryResult;
import com.bannerdeliver.domain.dto.BannerDeliverySource;

/** Banner 投放查询的 HTTP 响应视图。 */
public record BannerDeliveryVO(
        Long bannerId,
        Long productId,
        String url,
        String beginTime,
        String endTime,
        BannerDeliverySource source,
        String cacheDate) {

    /** 将内部查询结果转换为对外 VO。 */
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
