package com.bannerdeliver.domain.dto;

import java.time.LocalDate;
import java.util.Objects;

/** Banner 投放查询的内部结果，包含 Runtime、数据来源和缓存日期。 */
public record BannerDeliveryResult(
        BannerRuntimeDTO banner,
        BannerDeliverySource source,
        LocalDate cacheDate) {

    /** 校验 banner 和 source 均不为 null。 */
    public BannerDeliveryResult {
        Objects.requireNonNull(banner, "banner must not be null");
        Objects.requireNonNull(source, "source must not be null");
    }
}
