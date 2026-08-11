package com.bannerdeliver.domain.dto;

/** Banner 投放查询结果的数据来源。 */
public enum BannerDeliverySource {
    /** 当天缓存命中。 */
    TODAY,
    /** 历史日期兜底命中。 */
    PREVIOUS_DATE,
    /** 静态默认 Banner 兜底。 */
    STATIC_DEFAULT
}
