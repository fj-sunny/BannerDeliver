package com.bannerdeliver.domain.dto;

import java.time.LocalDate;
import java.util.Objects;

public record BannerDateCacheKey(Long productId, LocalDate date) {

    public BannerDateCacheKey {
        Objects.requireNonNull(productId, "productId must not be null");
        Objects.requireNonNull(date, "date must not be null");
    }
}
