package com.bannerdeliver.schedule;

import com.bannerdeliver.config.BannerProperties;
import com.bannerdeliver.domain.po.BannerInfo;
import com.bannerdeliver.service.BannerCacheService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BannerReconciliationServiceTest {

    @Test
    void shouldRepairStaleBannersAndReconcileKafkaConsumeRecords() {
        BannerCacheService cacheService = mock(BannerCacheService.class);
        BannerProperties properties = new BannerProperties();
        properties.getReconciliation().setWindowMinutes(5);
        BannerReconciliationService service = new BannerReconciliationService(
                cacheService, properties);
        BannerInfo latest = banner(20L);
        BannerInfo suspectedLost = banner(21L);
        BannerInfo consumedButStale = banner(22L);
        LocalDateTime windowStart = LocalDateTime.of(2026, 7, 20, 10, 0);
        LocalDateTime windowEnd = LocalDateTime.of(2026, 7, 20, 10, 5);

        when(cacheService.findUpdatedBetween(windowStart, windowEnd))
                .thenReturn(List.of(latest, suspectedLost, consumedButStale));
        when(cacheService.hasLatestRuntime(latest)).thenReturn(true);
        when(cacheService.hasLatestRuntime(suspectedLost)).thenReturn(false);
        when(cacheService.hasLatestRuntime(consumedButStale)).thenReturn(false);
        when(cacheService.repairFromMysql(21L, "schedule_202607201000_21"))
                .thenReturn(BannerCacheService.RefreshResult.REFRESHED);
        when(cacheService.repairFromMysql(22L, "schedule_202607201000_22"))
                .thenReturn(BannerCacheService.RefreshResult.REFRESHED);
        when(cacheService.findConsumedBannerIds("202607201000"))
                .thenReturn(Set.of(20L, 22L));

        BannerReconciliationService.BannerAuditResult result =
                service.reconcileWindow(windowStart, windowEnd);

        assertThat(result.dbChangedIds()).containsExactlyInAnyOrder(20L, 21L, 22L);
        assertThat(result.redisLatestIds()).containsExactly(20L);
        assertThat(result.scheduleRepairIds()).containsExactlyInAnyOrder(21L, 22L);
        assertThat(result.mqConsumedIds()).containsExactlyInAnyOrder(20L, 22L);
        assertThat(result.suspectedLostIds()).containsExactly(21L);
        assertThat(result.consumeButStaleIds()).containsExactly(22L);
        verify(cacheService).repairFromMysql(21L, "schedule_202607201000_21");
        verify(cacheService).repairFromMysql(22L, "schedule_202607201000_22");
    }

    private BannerInfo banner(Long bannerId) {
        return BannerInfo.builder()
                .bannerId(bannerId)
                .productId(10L)
                .beginTime("2026-07-20 10:00:00")
                .endTime("2026-07-20 23:59:59")
                .updateTime("2026-07-20 10:01:00")
                .build();
    }
}
