package com.bannerdeliver.schedule;

import com.bannerdeliver.cache.redis.BannerRedisRepository;
import com.bannerdeliver.config.BannerProperties;
import com.bannerdeliver.domain.po.BannerInfo;
import com.bannerdeliver.service.BannerCacheRefreshService;
import com.bannerdeliver.service.BannerSnapshotService;
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
        BannerSnapshotService snapshotService = mock(BannerSnapshotService.class);
        BannerRedisRepository redisRepository = mock(BannerRedisRepository.class);
        BannerCacheRefreshService refreshService = mock(BannerCacheRefreshService.class);
        BannerProperties properties = new BannerProperties();
        properties.getReconciliation().setWindowMinutes(5);
        BannerReconciliationService service = new BannerReconciliationService(
                snapshotService, redisRepository, refreshService, properties);
        BannerInfo latest = banner(20L);
        BannerInfo suspectedLost = banner(21L);
        BannerInfo consumedButStale = banner(22L);
        LocalDateTime windowStart = LocalDateTime.of(2026, 7, 20, 10, 0);
        LocalDateTime windowEnd = LocalDateTime.of(2026, 7, 20, 10, 5);

        when(snapshotService.findUpdatedBetween(windowStart, windowEnd))
                .thenReturn(List.of(latest, suspectedLost, consumedButStale));
        when(redisRepository.hasLatestRuntime(latest)).thenReturn(true);
        when(redisRepository.hasLatestRuntime(suspectedLost)).thenReturn(false);
        when(redisRepository.hasLatestRuntime(consumedButStale)).thenReturn(false);
        when(refreshService.repairFromMysql(21L, "schedule_202607201000_21"))
                .thenReturn(BannerCacheRefreshService.RefreshResult.REFRESHED);
        when(refreshService.repairFromMysql(22L, "schedule_202607201000_22"))
                .thenReturn(BannerCacheRefreshService.RefreshResult.REFRESHED);
        when(redisRepository.findConsumedBannerIds("202607201000"))
                .thenReturn(Set.of(20L, 22L));

        BannerReconciliationService.BannerAuditResult result =
                service.reconcilePreviousCompleteWindow(
                        LocalDateTime.of(2026, 7, 20, 10, 7, 30));

        assertThat(result.windowStart()).isEqualTo(windowStart);
        assertThat(result.windowEnd()).isEqualTo(windowEnd);
        assertThat(result.dbChangedIds()).containsExactlyInAnyOrder(20L, 21L, 22L);
        assertThat(result.redisLatestIds()).containsExactly(20L);
        assertThat(result.scheduleRepairIds()).containsExactlyInAnyOrder(21L, 22L);
        assertThat(result.mqConsumedIds()).containsExactlyInAnyOrder(20L, 22L);
        assertThat(result.suspectedLostIds()).containsExactly(21L);
        assertThat(result.consumeButStaleIds()).containsExactly(22L);
        assertThat(result.repairFailedIds()).isEmpty();
        verify(refreshService).repairFromMysql(21L, "schedule_202607201000_21");
        verify(refreshService).repairFromMysql(22L, "schedule_202607201000_22");
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
