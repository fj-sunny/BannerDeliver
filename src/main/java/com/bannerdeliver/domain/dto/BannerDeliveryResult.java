package com.bannerdeliver.domain.dto;

import java.time.LocalDate;
import java.util.Objects;

public record BannerDeliveryResult(
        BannerRuntimeDTO banner,
        BannerDeliverySource source,
        LocalDate cacheDate) {

    public BannerDeliveryResult {
        Objects.requireNonNull(banner, "banner must not be null");
        Objects.requireNonNull(source, "source must not be null");
    }
}
