package com.bannerdeliver.domain.vo;

import com.bannerdeliver.domain.dto.BannerDeliveryResult;
import com.bannerdeliver.domain.dto.BannerDeliverySource;

/**
 * Banner 投放查询的 HTTP 响应视图。
 * 对外隐藏 bucketCount、audienceBatch 等内部缓存字段。
 */
public record BannerDeliveryVO(
        /** 命中的 Banner ID。 */
        Long bannerId,
        /** 商品 ID。 */
        Long productId,
        /** 展示 URL。 */
        String url,
        /** 投放开始时间，Unix 毫秒。 */
        Long beginTime,
        /** 投放结束时间，Unix 毫秒。 */
        Long endTime,
        /** 数据来源：当天/历史兜底/静态默认。 */
        BannerDeliverySource source,
        /** 实际命中日期当天零点的 Unix 毫秒，静态兜底时为 null。 */
        Long cacheDate) {

    /** 将内部 BannerDeliveryResult 转换为对外 VO。 */
    public static BannerDeliveryVO from(BannerDeliveryResult result) {
        return new BannerDeliveryVO(
                result.banner().getBannerId(),
                result.banner().getProductId(),
                result.banner().getUrl(),
                result.banner().getBeginTime(),
                result.banner().getEndTime(),
                result.source(),
                result.cacheDate());
    }
}
