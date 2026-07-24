package com.bannerdeliver.utils;

import com.bannerdeliver.domain.dto.BannerDateCacheKey;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class BannerRedisKeyBuilderTest {

    private final BannerRedisKeyBuilder keyBuilder = new BannerRedisKeyBuilder();

    @Test
    void shouldBuildDesignSpecifiedKeys() {
        assertThat(keyBuilder.dateCacheKey(10L, LocalDate.of(2026, 7, 20)))
                .isEqualTo("product:10:date:20260720");
        assertThat(keyBuilder.audienceBucketKey(20L, "evt_20260720_001", 3))
                .isEqualTo("banner:audience:20:evt_20260720_001:bucket:3");
        assertThat(keyBuilder.consumeWindowKey("202607201000"))
                .isEqualTo("mq:consume:202607201000");
        assertThat(keyBuilder.dateKeyIndex(20L))
                .isEqualTo("banner:date-keys:{20}");
        assertThat(keyBuilder.versionKey(20L))
                .isEqualTo("banner:version:{20}");
        assertThat(keyBuilder.parseDateCacheKey("product:10:date:20260720"))
                .contains(new BannerDateCacheKey(10L, LocalDate.of(2026, 7, 20)));
    }
}
