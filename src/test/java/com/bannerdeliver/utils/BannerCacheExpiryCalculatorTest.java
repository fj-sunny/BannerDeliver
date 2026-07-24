package com.bannerdeliver.utils;

import com.bannerdeliver.config.BannerProperties;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class BannerCacheExpiryCalculatorTest {

    @Test
    void shouldExpireDateCacheAtDatePlusTwoDaysMidnight() {
        BannerProperties properties = new BannerProperties();
        properties.getCache().getRedis().setExpireAfterDateDays(2);
        properties.getCache().getRedis().setZoneId(ZoneId.of("Asia/Shanghai"));
        BannerCacheExpiryCalculator calculator = new BannerCacheExpiryCalculator(properties);

        Instant expireAt = calculator.dateCacheExpireAt(LocalDate.of(2026, 7, 20));

        assertThat(expireAt).isEqualTo(Instant.parse("2026-07-21T16:00:00Z"));
    }

    @Test
    void shouldUseStableBoundedJitter() {
        BannerProperties properties = new BannerProperties();
        properties.getCache().getRedis().setExpireJitterMaxSeconds(300);
        BannerCacheExpiryCalculator calculator = new BannerCacheExpiryCalculator(properties);
        Instant base = Instant.parse("2026-07-21T16:00:00Z");

        Instant first = calculator.withStableJitter(base, "banner:audience:20:evt:bucket:1");
        Instant second = calculator.withStableJitter(base, "banner:audience:20:evt:bucket:1");

        assertThat(first).isEqualTo(second);
        assertThat(first).isBetween(base, base.plusSeconds(300));
    }
}
