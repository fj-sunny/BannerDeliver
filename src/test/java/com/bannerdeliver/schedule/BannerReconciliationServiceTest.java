package com.bannerdeliver.schedule;

import com.bannerdeliver.config.BannerProperties;
import com.bannerdeliver.domain.dto.BannerEventType;
import com.bannerdeliver.domain.po.BannerInfo;
import com.bannerdeliver.service.BannerCacheService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BannerReconciliationServiceTest {

    @Test
    void shouldRepairStaleBanners() {
        BannerCacheService cacheService = mock(BannerCacheService.class);
        BannerProperties properties = new BannerProperties();
        properties.getReconciliation().setWindowMinutes(5L);
        BannerReconciliationService service = new BannerReconciliationService(
                cacheService, properties);
        BannerInfo latest = banner(20L);
        BannerInfo staleOne = banner(21L);
        BannerInfo staleTwo = banner(22L);
        Long windowStart = 1784512800000L;
        Long windowEnd = 1784513100000L;

        when(cacheService.findUpdatedBetween(windowStart, windowEnd))
                .thenReturn(List.of(latest, staleOne, staleTwo));
        when(cacheService.hasLatestRuntime(latest)).thenReturn(true);
        when(cacheService.hasLatestRuntime(staleOne)).thenReturn(false);
        when(cacheService.hasLatestRuntime(staleTwo)).thenReturn(false);

        BannerReconciliationService.BannerAuditResult result =
                service.reconcileWindow(windowStart, windowEnd);

        assertThat(result.dbChangedIds()).containsExactlyInAnyOrder(20L, 21L, 22L);
        assertThat(result.redisLatestIds()).containsExactly(20L);
        assertThat(result.scheduleRepairIds()).containsExactlyInAnyOrder(21L, 22L);
        verify(cacheService).refreshBannerCache(
                21L, "schedule_1784512800000_21", BannerEventType.FULL_UPDATE);
        verify(cacheService).refreshBannerCache(
                22L, "schedule_1784512800000_22", BannerEventType.FULL_UPDATE);
    }

    private BannerInfo banner(Long bannerId) {
        return BannerInfo.builder()
                .bannerId(bannerId)
                .productId(10L)
                .beginTime(1784512800000L)
                .endTime(1784563199000L)
                .updateTime(1784512860000L)
                .build();
    }
}
