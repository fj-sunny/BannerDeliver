package com.bannerdeliver.utils;

import com.bannerdeliver.config.BannerProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BannerCacheExpiryCalculatorTest {

    @Test
    void shouldExpireDateCacheAtDatePlusTwoDaysMidnight() {
        BannerProperties properties = new BannerProperties();
        properties.getCache().getRedis().setExpireAfterDateDays(2L);
        BannerCacheExpiryCalculator calculator = new BannerCacheExpiryCalculator(properties);

        Long expireAt = calculator.dateCacheExpireAt(1784476800000L);

        assertThat(expireAt).isEqualTo(1784649600000L);
    }

    @Test
    void shouldUseStableBoundedJitter() {
        BannerProperties properties = new BannerProperties();
        properties.getCache().getRedis().setExpireJitterMaxSeconds(300L);
        BannerCacheExpiryCalculator calculator = new BannerCacheExpiryCalculator(properties);
        Long base = 1784649600000L;

        Long first = calculator.withStableJitter(base, "banner:audience:20:evt:bucket:1");
        Long second = calculator.withStableJitter(base, "banner:audience:20:evt:bucket:1");

        assertThat(first).isEqualTo(second);
        assertThat(first).isBetween(base, base + 300_000L);
    }
}
