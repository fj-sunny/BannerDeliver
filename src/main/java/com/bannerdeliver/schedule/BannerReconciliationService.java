package com.bannerdeliver.schedule;

import com.bannerdeliver.cache.redis.BannerRedisRepository;
import com.bannerdeliver.config.BannerProperties;
import com.bannerdeliver.domain.po.BannerInfo;
import com.bannerdeliver.service.BannerCacheRefreshService;
import com.bannerdeliver.service.BannerSnapshotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class BannerReconciliationService {

    private static final DateTimeFormatter WINDOW_KEY_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMddHHmm");
    private static final DateTimeFormatter WINDOW_LOG_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final BannerSnapshotService snapshotService;
    private final BannerRedisRepository redisRepository;
    private final BannerCacheRefreshService refreshService;
    private final BannerProperties properties;

    public BannerAuditResult reconcilePreviousCompleteWindow(LocalDateTime now) {
        int windowMinutes = properties.getReconciliation().getWindowMinutes();
        LocalDateTime currentWindowStart = floorToWindow(now, windowMinutes);
        return reconcileWindow(
                currentWindowStart.minusMinutes(windowMinutes), currentWindowStart);
    }

    BannerAuditResult reconcileWindow(LocalDateTime windowStart, LocalDateTime windowEnd) {
        String window = WINDOW_KEY_FORMATTER.format(windowStart);
        List<BannerInfo> changedBanners = snapshotService.findUpdatedBetween(
                windowStart, windowEnd);
        Set<Long> dbChangedIds = new LinkedHashSet<>();
        Set<Long> redisLatestIds = new LinkedHashSet<>();
        Set<Long> scheduleRepairIds = new LinkedHashSet<>();
        Set<Long> repairFailedIds = new LinkedHashSet<>();

        for (BannerInfo banner : changedBanners) {
            Long bannerId = banner.getBannerId();
            dbChangedIds.add(bannerId);
            try {
                if (redisRepository.hasLatestRuntime(banner)) {
                    redisLatestIds.add(bannerId);
                    continue;
                }
                BannerCacheRefreshService.RefreshResult refreshResult =
                        refreshService.repairFromMysql(
                                bannerId, repairAudienceBatch(window, bannerId));
                if (refreshResult == BannerCacheRefreshService.RefreshResult.REFRESHED
                        || refreshResult == BannerCacheRefreshService.RefreshResult.DELETED) {
                    scheduleRepairIds.add(bannerId);
                }
            } catch (RuntimeException exception) {
                repairFailedIds.add(bannerId);
                log.error("Banner schedule repair failed: window={}, bannerId={}",
                        window, bannerId, exception);
            }
        }

        Set<Long> mqConsumedIds = redisRepository.findConsumedBannerIds(window);
        Set<Long> suspectedLostIds = difference(scheduleRepairIds, mqConsumedIds);
        Set<Long> consumeButStaleIds = intersection(scheduleRepairIds, mqConsumedIds);
        BannerAuditResult result = new BannerAuditResult(
                windowStart,
                windowEnd,
                dbChangedIds,
                redisLatestIds,
                scheduleRepairIds,
                mqConsumedIds,
                suspectedLostIds,
                consumeButStaleIds,
                repairFailedIds);
        logAudit(result);
        return result;
    }

    private LocalDateTime floorToWindow(LocalDateTime time, int windowMinutes) {
        LocalDateTime startOfDay = time.toLocalDate().atStartOfDay();
        long minuteOfDay = time.getHour() * 60L + time.getMinute();
        return startOfDay.plusMinutes(minuteOfDay / windowMinutes * windowMinutes);
    }

    private String repairAudienceBatch(String window, Long bannerId) {
        return "schedule_" + window + "_" + bannerId;
    }

    private Set<Long> difference(Set<Long> left, Set<Long> right) {
        Set<Long> result = new HashSet<>(left);
        result.removeAll(right);
        return Set.copyOf(result);
    }

    private Set<Long> intersection(Set<Long> left, Set<Long> right) {
        Set<Long> result = new HashSet<>(left);
        result.retainAll(right);
        return Set.copyOf(result);
    }

    private void logAudit(BannerAuditResult result) {
        log.info("[BannerAudit] window={}~{}, dbChanged={}, redisAlreadyLatest={}, "
                        + "scheduleRepaired={}, mqConsumed={}, suspectedLost={}, "
                        + "consumeButStale={}, repairFailed={}, dbChangedIds={}, "
                        + "redisLatestIds={}, scheduleRepairIds={}, mqConsumedIds={}, "
                        + "suspectedLostIds={}, consumeButStaleIds={}, repairFailedIds={}",
                WINDOW_LOG_FORMATTER.format(result.windowStart()),
                WINDOW_LOG_FORMATTER.format(result.windowEnd()),
                result.dbChangedIds().size(),
                result.redisLatestIds().size(),
                result.scheduleRepairIds().size(),
                result.mqConsumedIds().size(),
                result.suspectedLostIds().size(),
                result.consumeButStaleIds().size(),
                result.repairFailedIds().size(),
                sorted(result.dbChangedIds()),
                sorted(result.redisLatestIds()),
                sorted(result.scheduleRepairIds()),
                sorted(result.mqConsumedIds()),
                sorted(result.suspectedLostIds()),
                sorted(result.consumeButStaleIds()),
                sorted(result.repairFailedIds()));
    }

    private List<Long> sorted(Set<Long> ids) {
        return ids.stream().sorted().toList();
    }

    public record BannerAuditResult(
            LocalDateTime windowStart,
            LocalDateTime windowEnd,
            Set<Long> dbChangedIds,
            Set<Long> redisLatestIds,
            Set<Long> scheduleRepairIds,
            Set<Long> mqConsumedIds,
            Set<Long> suspectedLostIds,
            Set<Long> consumeButStaleIds,
            Set<Long> repairFailedIds) {

        public BannerAuditResult {
            dbChangedIds = Set.copyOf(dbChangedIds);
            redisLatestIds = Set.copyOf(redisLatestIds);
            scheduleRepairIds = Set.copyOf(scheduleRepairIds);
            mqConsumedIds = Set.copyOf(mqConsumedIds);
            suspectedLostIds = Set.copyOf(suspectedLostIds);
            consumeButStaleIds = Set.copyOf(consumeButStaleIds);
            repairFailedIds = Set.copyOf(repairFailedIds);
        }
    }
}
