package com.bannerdeliver.domain.dto;

import java.time.LocalDate;
import java.util.Objects;

/** Guava L1 本地缓存的 Key：商品 ID + 业务日期。 */
public record BannerDateCacheKey(Long productId, LocalDate date) {

    /** 校验 productId 和 date 均不为 null。 */
    public BannerDateCacheKey {
        Objects.requireNonNull(productId, "productId must not be null");
        Objects.requireNonNull(date, "date must not be null");
    }
}
