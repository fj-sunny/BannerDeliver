package com.bannerdeliver.schedule;

import com.bannerdeliver.config.BannerProperties;
import com.bannerdeliver.domain.dto.BannerEventType;
import com.bannerdeliver.domain.po.BannerInfo;
import com.bannerdeliver.service.BannerCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 定时对账：检查 MySQL 变更是否已经同步到 Redis。
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

    private final BannerCacheService cacheService;
    private final BannerProperties properties;

    /** 定时任务入口，处理上一个已结束的完整对账窗口。 */
    @Scheduled(cron = "${banner.reconciliation.cron:0 */5 * * * *}")
    public void reconcile() {
        reconcilePreviousCompleteWindow(System.currentTimeMillis());
    }

    /** 根据当前时间计算并处理上一个完整对账窗口。 */
    public BannerAuditResult reconcilePreviousCompleteWindow(Long now) {
        Long windowMinutes = properties.getReconciliation().getWindowMinutes();
        Long currentWindowStart = floorToWindow(now, windowMinutes);
        return reconcileWindow(
                currentWindowStart - windowMinutes * 60_000L, currentWindowStart);
    }

    /** 对指定时间窗口执行 MySQL/Redis 对账与补偿修复。 */
    BannerAuditResult reconcileWindow(Long windowStart, Long windowEnd) {
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
                cacheService.refreshBannerCache(
                        bannerId,
                        "schedule_" + windowStart + "_" + bannerId,
                        BannerEventType.FULL_UPDATE);
                scheduleRepairIds.add(bannerId);
            } catch (RuntimeException exception) {
                repairFailedIds.add(bannerId);
                log.error("Banner schedule repair failed: window={}, bannerId={}",
                        windowStart, bannerId, exception);
            }
        }

        BannerAuditResult result = new BannerAuditResult(
                windowStart,
                windowEnd,
                dbChangedIds,
                redisLatestIds,
                scheduleRepairIds,
                repairFailedIds);
        logAudit(result);
        return result;
    }

    /** 将时间向下取整到最近的对账窗口起始时刻。 */
    private Long floorToWindow(Long time, Long windowMinutes) {
        Long windowMillis = windowMinutes * 60_000L;
        return Math.floorDiv(time, windowMillis) * windowMillis;
    }

    /** 输出 [BannerAudit] 对账日志，包含各集合的数量与 ID 明细。 */
    private void logAudit(BannerAuditResult result) {
        log.info("[BannerAudit] window={}~{}, dbChanged={}, redisAlreadyLatest={}, "
                        + "scheduleRepaired={}, repairFailed={}, dbChangedIds={}, "
                        + "redisLatestIds={}, scheduleRepairIds={}, repairFailedIds={}",
                result.windowStart(),
                result.windowEnd(),
                result.dbChangedIds().size(),
                result.redisLatestIds().size(),
                result.scheduleRepairIds().size(),
                result.repairFailedIds().size(),
                sorted(result.dbChangedIds()),
                sorted(result.redisLatestIds()),
                sorted(result.scheduleRepairIds()),
                sorted(result.repairFailedIds()));
    }

    /** 将 bannerId 集合排序后输出，便于日志阅读。 */
    private List<Long> sorted(Set<Long> ids) {
        return ids.stream().sorted().toList();
    }

    /** 对账结果快照，各 ID 集合在构造时转为不可变 Set。 */
    public record BannerAuditResult(
            /** 对账窗口起始时刻（含）。 */
            Long windowStart,
            /** 对账窗口结束时刻（不含）。 */
            Long windowEnd,
            /** MySQL 在该窗口内有 update_time 变更的 bannerId。 */
            Set<Long> dbChangedIds,
            /** Redis Runtime 已与 MySQL 对齐、无需修复的 bannerId。 */
            Set<Long> redisLatestIds,
            /** 定时任务已成功修复 Redis 的 bannerId。 */
            Set<Long> scheduleRepairIds,
            /** 修复过程中抛异常的 bannerId。 */
            Set<Long> repairFailedIds) {

        /** 规范化各集合并拷贝为不可变 Set。 */
        public BannerAuditResult {
            dbChangedIds = Set.copyOf(dbChangedIds);
            redisLatestIds = Set.copyOf(redisLatestIds);
            scheduleRepairIds = Set.copyOf(scheduleRepairIds);
            repairFailedIds = Set.copyOf(repairFailedIds);
        }
    }
}
