package com.bannerdeliver.service;

import com.bannerdeliver.config.BannerProperties;
import com.bannerdeliver.domain.dto.BannerDeliveryResult;
import com.bannerdeliver.domain.dto.BannerDeliverySource;
import com.bannerdeliver.domain.dto.BannerRuntimeDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class BannerDeliveryServiceTest {

    private static final LocalDateTime NOW =
            LocalDateTime.of(2026, 7, 20, 12, 0);
    private final BannerCacheService cacheService = mock(BannerCacheService.class);
    private final BannerProperties properties = new BannerProperties();
    private BannerDeliveryService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(
                Instant.parse("2026-07-20T04:00:00Z"),
                ZoneId.of("Asia/Shanghai"));
        service = new BannerDeliveryService(cacheService, properties, clock);
    }

    @Test
    void shouldReturnTodayBannerWithoutReadingPreviousDate() {
        BannerRuntimeDTO today = runtime(20L, "today.png");
        when(cacheService.getBanners(10L, NOW.toLocalDate())).thenReturn(List.of(today));
        when(cacheService.isAudienceMember(today, "1001")).thenReturn(true);

        BannerDeliveryResult result = service.query(10L, "1001");

        assertThat(result.banner()).isSameAs(today);
        assertThat(result.source()).isEqualTo(BannerDeliverySource.TODAY);
        assertThat(result.cacheDate()).isEqualTo(LocalDate.of(2026, 7, 20));
    }

    @Test
    void shouldUsePreviousDateWithSameTimeOfDayAsFallback() {
        BannerRuntimeDTO yesterday = runtime(19L, "yesterday.png");
        when(cacheService.getBanners(10L, NOW.toLocalDate())).thenReturn(List.of());
        when(cacheService.getBanners(10L, NOW.minusDays(1).toLocalDate()))
                .thenReturn(List.of(yesterday));
        when(cacheService.isAudienceMember(yesterday, "1001")).thenReturn(true);

        BannerDeliveryResult result = service.query(10L, "1001");

        assertThat(result.banner()).isSameAs(yesterday);
        assertThat(result.source()).isEqualTo(BannerDeliverySource.PREVIOUS_DATE);
        assertThat(result.cacheDate()).isEqualTo(LocalDate.of(2026, 7, 19));
    }

    @Test
    void shouldReturnConfiguredStaticDefaultWhenNoAudienceMatches() {
        when(cacheService.getBanners(10L, NOW.toLocalDate())).thenReturn(List.of());
        when(cacheService.getBanners(10L, NOW.minusDays(1).toLocalDate())).thenReturn(List.of());
        properties.getDelivery().getDefaultBanner().setBannerId(0L);
        properties.getDelivery().getDefaultBanner().setUrl("https://cdn/default.png");

        BannerDeliveryResult result = service.query(10L, "1001");

        assertThat(result.source()).isEqualTo(BannerDeliverySource.STATIC_DEFAULT);
        assertThat(result.cacheDate()).isNull();
        assertThat(result.banner().getBannerId()).isZero();
        assertThat(result.banner().getProductId()).isEqualTo(10L);
        assertThat(result.banner().getUrl()).isEqualTo("https://cdn/default.png");
    }

    @Test
    void shouldFilterCandidatesByStatusTimeAndAudience() {
        BannerRuntimeDTO eligible = runtime(20L, "eligible.png");
        BannerRuntimeDTO offline = BannerRuntimeDTO.builder()
                .bannerId(21L).status(0)
                .beginTime("2026-07-20 10:00:00").endTime("2026-07-20 23:59:59").build();
        BannerRuntimeDTO audienceMiss = runtime(22L, "miss.png");
        when(cacheService.getBanners(10L, NOW.toLocalDate()))
                .thenReturn(List.of(audienceMiss, offline, eligible));
        when(cacheService.isAudienceMember(eligible, "1001")).thenReturn(true);
        when(cacheService.isAudienceMember(audienceMiss, "1001")).thenReturn(false);

        assertThat(service.findEligibleBanners(10L, "1001", NOW))
                .extracting(BannerRuntimeDTO::getBannerId)
                .containsExactly(20L);
    }

    private BannerRuntimeDTO runtime(Long bannerId, String url) {
        return BannerRuntimeDTO.builder()
                .bannerId(bannerId)
                .productId(10L)
                .url(url)
                .status(1)
                .beginTime("2026-07-19 00:00:00")
                .endTime("2026-07-20 23:59:59")
                .build();
    }
}
