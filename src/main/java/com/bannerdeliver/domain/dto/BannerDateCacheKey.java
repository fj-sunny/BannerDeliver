package com.bannerdeliver.domain.dto;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Guava L1 本地缓存的 Key。
 * 由商品 ID + 业务日期唯一确定一组 Banner Runtime 列表。
 */
public record BannerDateCacheKey(
        /** 商品 ID。 */
        Long productId,
        /** 业务日期（不含时分秒）。 */
        LocalDate date) {

    /** 校验 productId 和 date 均不为 null。 */
    public BannerDateCacheKey {
        Objects.requireNonNull(productId, "productId must not be null");
        Objects.requireNonNull(date, "date must not be null");
    }
}
