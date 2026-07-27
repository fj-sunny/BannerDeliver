package com.bannerdeliver.domain.dto;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Banner 投放查询的内部结果。
 * 包含命中的 Runtime、数据来源标识和实际使用的缓存业务日期。
 */
public record BannerDeliveryResult(
        /** 命中的 Banner 运行时快照。 */
        BannerRuntimeDTO banner,
        /** 数据来源：当天/历史兜底/静态默认。 */
        BannerDeliverySource source,
        /** 实际读取缓存的业务日期；静态兜底时为 null。 */
        LocalDate cacheDate) {

    /** 校验 banner 和 source 均不为 null。 */
    public BannerDeliveryResult {
        Objects.requireNonNull(banner, "banner must not be null");
        Objects.requireNonNull(source, "source must not be null");
    }
}
