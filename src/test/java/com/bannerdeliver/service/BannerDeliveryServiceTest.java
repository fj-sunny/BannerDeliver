package com.bannerdeliver.service;

import com.bannerdeliver.config.BannerProperties;
import com.bannerdeliver.domain.dto.BannerDeliveryResult;
import com.bannerdeliver.domain.dto.BannerDeliverySource;
import com.bannerdeliver.domain.dto.BannerRuntimeDTO;
import com.bannerdeliver.utils.BannerTimeUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class BannerDeliveryServiceTest {

    private static final Long NOW = System.currentTimeMillis();
    private static final Long TODAY = BannerTimeUtils.startOfDay(NOW, 28_800_000L);
    private static final Long YESTERDAY = TODAY - BannerTimeUtils.DAY_MILLIS;
    private final BannerCacheService cacheService = mock(BannerCacheService.class);
    private final BannerProperties properties = new BannerProperties();
    private BannerDeliveryService service;

    @BeforeEach
    void setUp() {
        service = new BannerDeliveryService(cacheService, properties);
    }

    @Test
    void shouldReturnTodayBannerWithoutReadingPreviousDate() {
        BannerRuntimeDTO today = runtime(20L, "today.png");
        when(cacheService.getBanners(10L, TODAY)).thenReturn(List.of(today));
        when(cacheService.isAudienceMember(today, "1001")).thenReturn(true);

        BannerDeliveryResult result = service.query(10L, "1001");

        assertThat(result.banner()).isSameAs(today);
        assertThat(result.source()).isEqualTo(BannerDeliverySource.TODAY);
        assertThat(result.cacheDate()).isEqualTo(TODAY);
    }

    @Test
    void shouldUsePreviousDateWithSameTimeOfDayAsFallback() {
        BannerRuntimeDTO yesterday = runtime(19L, "yesterday.png");
        when(cacheService.getBanners(10L, TODAY)).thenReturn(List.of());
        when(cacheService.getBanners(10L, YESTERDAY))
                .thenReturn(List.of(yesterday));
        when(cacheService.isAudienceMember(yesterday, "1001")).thenReturn(true);

        BannerDeliveryResult result = service.query(10L, "1001");

        assertThat(result.banner()).isSameAs(yesterday);
        assertThat(result.source()).isEqualTo(BannerDeliverySource.PREVIOUS_DATE);
        assertThat(result.cacheDate()).isEqualTo(YESTERDAY);
    }

    @Test
    void shouldReturnConfiguredStaticDefaultWhenNoAudienceMatches() {
        when(cacheService.getBanners(10L, TODAY)).thenReturn(List.of());
        when(cacheService.getBanners(10L, YESTERDAY)).thenReturn(List.of());
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
                .beginTime(NOW - 1000L).endTime(NOW + 1000L).build();
        BannerRuntimeDTO audienceMiss = runtime(22L, "miss.png");
        when(cacheService.getBanners(10L, TODAY))
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
                .beginTime(NOW - 2 * BannerTimeUtils.DAY_MILLIS)
                .endTime(NOW + BannerTimeUtils.DAY_MILLIS)
                .build();
    }
}
