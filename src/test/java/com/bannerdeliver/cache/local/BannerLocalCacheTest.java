package com.bannerdeliver.cache.local;

import com.bannerdeliver.cache.BannerRuntimeCache;
import com.bannerdeliver.domain.dto.BannerDateCacheKey;
import com.bannerdeliver.domain.dto.BannerRuntimeDTO;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BannerLocalCacheTest {

    @Test
    void shouldLoadOnceAndReloadAfterInvalidation() {
        LocalDate date = LocalDate.of(2026, 7, 20);
        AtomicInteger redisLoads = new AtomicInteger();
        AtomicReference<List<BannerRuntimeDTO>> redisValue = new AtomicReference<>(
                List.of(runtime(20L)));
        BannerRuntimeCache runtimeCache = new BannerRuntimeCache() {
            @Override
            public List<BannerRuntimeDTO> findBanners(Long productId, LocalDate queryDate) {
                redisLoads.incrementAndGet();
                return redisValue.get();
            }

            @Override
            public boolean isAudienceMember(BannerRuntimeDTO runtime, String userId) {
                return false;
            }
        };
        Cache<BannerDateCacheKey, List<BannerRuntimeDTO>> cache = CacheBuilder.newBuilder()
                .maximumSize(10000)
                .build();
        BannerLocalCache service = new BannerLocalCache(cache, runtimeCache);

        List<BannerRuntimeDTO> first = service.getBanners(10L, date);
        redisValue.set(List.of(runtime(21L)));
        List<BannerRuntimeDTO> cached = service.getBanners(10L, date);

        assertThat(first).extracting(BannerRuntimeDTO::getBannerId).containsExactly(20L);
        assertThat(cached).extracting(BannerRuntimeDTO::getBannerId).containsExactly(20L);
        assertThat(redisLoads).hasValue(1);
        assertThatThrownBy(() -> cached.add(runtime(22L)))
                .isInstanceOf(UnsupportedOperationException.class);

        service.invalidate(10L, date);
        assertThat(service.getBanners(10L, date))
                .extracting(BannerRuntimeDTO::getBannerId)
                .containsExactly(21L);
        assertThat(redisLoads).hasValue(2);
    }

    private BannerRuntimeDTO runtime(Long bannerId) {
        return BannerRuntimeDTO.builder().bannerId(bannerId).build();
    }
}
