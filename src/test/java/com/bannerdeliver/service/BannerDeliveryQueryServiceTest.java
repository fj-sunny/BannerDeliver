package com.bannerdeliver.service;

import com.bannerdeliver.cache.BannerRuntimeCache;
import com.bannerdeliver.cache.local.BannerLocalCache;
import com.bannerdeliver.domain.dto.BannerDateCacheKey;
import com.bannerdeliver.domain.dto.BannerRuntimeDTO;
import com.google.common.cache.CacheBuilder;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BannerDeliveryQueryServiceTest {

    @Test
    void shouldFilterByStatusTimeAndAudience() {
        BannerRuntimeDTO eligible = runtime(20L, 1,
                "2026-07-20 10:00:00", "2026-07-20 23:59:59");
        BannerRuntimeDTO offline = runtime(21L, 0,
                "2026-07-20 10:00:00", "2026-07-20 23:59:59");
        BannerRuntimeDTO expired = runtime(22L, 1,
                "2026-07-20 08:00:00", "2026-07-20 09:00:00");
        BannerRuntimeDTO audienceMiss = runtime(23L, 1,
                "2026-07-20 10:00:00", "2026-07-20 23:59:59");
        BannerRuntimeCache runtimeCache = new BannerRuntimeCache() {
            @Override
            public List<BannerRuntimeDTO> findBanners(Long productId, LocalDate date) {
                return List.of(audienceMiss, expired, offline, eligible);
            }

            @Override
            public boolean isAudienceMember(BannerRuntimeDTO runtime, String userId) {
                return runtime.getBannerId().equals(20L);
            }
        };
        BannerLocalCache localCache = new BannerLocalCache(
                CacheBuilder.<BannerDateCacheKey, List<BannerRuntimeDTO>>newBuilder().build(),
                runtimeCache);
        BannerDeliveryQueryService service =
                new BannerDeliveryQueryService(localCache, runtimeCache);

        List<BannerRuntimeDTO> result = service.findEligibleBanners(
                10L, "1001", LocalDateTime.of(2026, 7, 20, 12, 0));

        assertThat(result).extracting(BannerRuntimeDTO::getBannerId).containsExactly(20L);
    }

    private BannerRuntimeDTO runtime(Long bannerId, int status, String beginTime, String endTime) {
        return BannerRuntimeDTO.builder()
                .bannerId(bannerId)
                .status(status)
                .beginTime(beginTime)
                .endTime(endTime)
                .bucketCount(1)
                .audienceBatch("evt")
                .build();
    }
}
