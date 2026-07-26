package com.bannerdeliver.schedule;

import com.bannerdeliver.config.BannerProperties;
import com.bannerdeliver.domain.po.BannerInfo;
import com.bannerdeliver.service.BannerCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 定时对账：MySQL 变更 vs Redis 缓存 vs Kafka 消费窗口。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "banner.reconciliation",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class BannerReconciliationService {

    private static final DateTimeFormatter WINDOW_KEY_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMddHHmm");
    private static final DateTimeFormatter WINDOW_LOG_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final BannerCacheService cacheService;
    private final BannerProperties properties;

    /** 定时任务入口，处理上一个已结束的完整对账窗口。 */
    @Scheduled(
            cron = "${banner.reconciliation.cron:0 */5 * * * *}",
            zone = "${banner.cache.redis.zone-id:Asia/Shanghai}")
    public void reconcile() {
        reconcilePreviousCompleteWindow(
                LocalDateTime.now(properties.getCache().getRedis().getZoneId()));
    }

    /** 根据当前时间计算并处理上一个完整对账窗口。 */
    public BannerAuditResult reconcilePreviousCompleteWindow(LocalDateTime now) {
        int windowMinutes = properties.getReconciliation().getWindowMinutes();
        LocalDateTime currentWindowStart = floorToWindow(now, windowMinutes);
        return reconcileWindow(
                currentWindowStart.minusMinutes(windowMinutes), currentWindowStart);
    }

    /** 对指定时间窗口执行 MySQL/Redis/Kafka 三方对账与补偿修复。 */
    BannerAuditResult reconcileWindow(LocalDateTime windowStart, LocalDateTime windowEnd) {
        String window = WINDOW_KEY_FORMATTER.format(windowStart);
        List<BannerInfo> changedBanners = cacheService.findUpdatedBetween(windowStart, windowEnd);
        Set<Long> dbChangedIds = new LinkedHashSet<>();
        Set<Long> redisLatestIds = new LinkedHashSet<>();
        Set<Long> scheduleRepairIds = new LinkedHashSet<>();
        Set<Long> repairFailedIds = new LinkedHashSet<>();

        for (BannerInfo banner : changedBanners) {
            Long bannerId = banner.getBannerId();
            dbChangedIds.add(bannerId);
            try {
                if (cacheService.hasLatestRuntime(banner)) {
                    redisLatestIds.add(bannerId);
                    continue;
                }
                BannerCacheService.RefreshResult refreshResult =
                        cacheService.repairFromMysql(
                                bannerId, repairAudienceBatch(window, bannerId));
                if (refreshResult == BannerCacheService.RefreshResult.REFRESHED
                        || refreshResult == BannerCacheService.RefreshResult.DELETED) {
                    scheduleRepairIds.add(bannerId);
                }
            } catch (RuntimeException exception) {
                repairFailedIds.add(bannerId);
                log.error("Banner schedule repair failed: window={}, bannerId={}",
                        window, bannerId, exception);
            }
        }

        Set<Long> mqConsumedIds = cacheService.findConsumedBannerIds(window);
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

    /** 将时间向下取整到最近的对账窗口起始时刻。 */
    private LocalDateTime floorToWindow(LocalDateTime time, int windowMinutes) {
        LocalDateTime startOfDay = time.toLocalDate().atStartOfDay();
        long minuteOfDay = time.getHour() * 60L + time.getMinute();
        return startOfDay.plusMinutes(minuteOfDay / windowMinutes * windowMinutes);
    }

    /** 生成定时修复使用的稳定 audienceBatch 标识。 */
    private String repairAudienceBatch(String window, Long bannerId) {
        return "schedule_" + window + "_" + bannerId;
    }

    /** 计算两个 bannerId 集合的差集。 */
    private Set<Long> difference(Set<Long> left, Set<Long> right) {
        Set<Long> result = new HashSet<>(left);
        result.removeAll(right);
        return Set.copyOf(result);
    }

    /** 计算两个 bannerId 集合的交集。 */
    private Set<Long> intersection(Set<Long> left, Set<Long> right) {
        Set<Long> result = new HashSet<>(left);
        result.retainAll(right);
        return Set.copyOf(result);
    }

    /** 输出 [BannerAudit] 对账日志，包含各集合的数量与 ID 明细。 */
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

    /** 将 bannerId 集合排序后输出，便于日志阅读。 */
    private List<Long> sorted(Set<Long> ids) {
        return ids.stream().sorted().toList();
    }

    /** 对账结果快照，各集合在构造时转为不可变 Set。 */
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

        /** 规范化各集合并拷贝为不可变 Set。 */
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
