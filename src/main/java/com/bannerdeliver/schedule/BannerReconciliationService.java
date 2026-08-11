package com.bannerdeliver.schedule; // 定时对账任务所在包

import com.bannerdeliver.config.BannerProperties; // 引入 Banner 配置（含对账窗口、cron）
import com.bannerdeliver.domain.dto.BannerEventType; // 引入缓存刷新事件类型枚举
import com.bannerdeliver.domain.po.BannerInfo; // 引入 MySQL Banner 实体
import com.bannerdeliver.service.BannerCacheService; // 引入缓存服务（查询变更、校验、修复）
import lombok.RequiredArgsConstructor; // 引入 Lombok 构造器注入注解
import lombok.extern.slf4j.Slf4j; // 引入 Lombok 日志注解
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty; // 引入按配置开关 Bean 的注解
import org.springframework.scheduling.annotation.Scheduled; // 引入定时任务注解
import org.springframework.stereotype.Service; // 引入 Spring Service 注解

import java.util.LinkedHashSet; // 引入保序且去重的 Set 实现
import java.util.List; // 引入 List 接口
import java.util.Set; // 引入 Set 接口

/**
 * 定时对账：检查 MySQL 变更是否已经同步到 Redis。
 *
 * <p>若 Redis Runtime 缺失或 updateTime 落后于 MySQL，则触发 FULL_UPDATE 补偿刷新。
 * MySQL 写库仍由运营端负责；本类只读 MySQL 并修复 Redis/LocalCache。</p>
 */
@Slf4j // 自动生成日志对象 log
@Service // 注册为 Spring 业务 Bean
@RequiredArgsConstructor // 为 final 字段生成构造器，实现依赖注入
@ConditionalOnProperty( // 仅当配置开启时才创建本 Bean
        prefix = "banner.reconciliation", // 配置前缀
        name = "enabled", // 配置项名：banner.reconciliation.enabled
        havingValue = "true", // 值为 true 时启用
        matchIfMissing = true) // 未配置时默认启用
public class BannerReconciliationService { // 定时对账服务类开始

    private final BannerCacheService cacheService; // 缓存服务：查变更、判齐、刷新 Redis
    private final BannerProperties properties; // 读取对账窗口分钟数等配置

    /** 定时任务入口，处理上一个已结束的完整对账窗口。 */
    @Scheduled(cron = "${banner.reconciliation.cron:0 */5 * * * *}") // 默认每 5 分钟触发一次
    public void reconcile() { // Spring 调度入口方法
        reconcilePreviousCompleteWindow(System.currentTimeMillis()); // 用当前时间对齐并处理上一完整窗口
    } // reconcile 结束

    /** 根据当前时间计算并处理上一个完整对账窗口。 */
    public BannerAuditResult reconcilePreviousCompleteWindow(Long now) { // 对外可测的窗口计算入口
        Long windowMinutes = properties.getReconciliation().getWindowMinutes(); // 读取对账窗口宽度（分钟）
        Long currentWindowStart = floorToWindow(now, windowMinutes); // 把 now 向下取整到当前窗口起点
        return reconcileWindow( // 处理「上一个已结束」窗口，避免扫到尚未结束的当前窗口
                currentWindowStart - windowMinutes * 60_000L, // 上一窗口起点 = 当前窗口起点 - 窗口毫秒数
                currentWindowStart); // 上一窗口终点（不含）= 当前窗口起点
    } // reconcilePreviousCompleteWindow 结束

    /** 对指定时间窗口执行 MySQL/Redis 对账与补偿修复。 */
    BannerAuditResult reconcileWindow(Long windowStart, Long windowEnd) { // 对 [windowStart, windowEnd) 做对账
        List<BannerInfo> changedBanners = cacheService.findUpdatedBetween(windowStart, windowEnd); // 查窗口内 MySQL update_time 有变更的 Banner
        Set<Long> dbChangedIds = new LinkedHashSet<>(); // 记录窗口内发生变更的 bannerId（保序）
        Set<Long> redisLatestIds = new LinkedHashSet<>(); // 记录 Redis 已与 MySQL 对齐、无需修复的 ID
        Set<Long> scheduleRepairIds = new LinkedHashSet<>(); // 记录本次定时任务成功修复的 ID
        Set<Long> repairFailedIds = new LinkedHashSet<>(); // 记录修复抛异常的 ID

        for (BannerInfo banner : changedBanners) { // 逐个处理变更 Banner
            Long bannerId = banner.getBannerId(); // 取出主键，后续写入各结果集合
            dbChangedIds.add(bannerId); // 先记入「库侧有变更」集合
            try { // 单个 Banner 失败不影响其他 Banner
                if (cacheService.hasLatestRuntime(banner)) { // Redis 各投放日 Runtime 都存在且 updateTime 不落后
                    redisLatestIds.add(bannerId); // 记为已对齐
                    continue; // 无需修复，处理下一个
                } // if 结束
                cacheService.refreshBannerCache( // Redis 落后：触发完整刷新（人群 + 日期 Hash）
                        bannerId, // 目标 Banner
                        "schedule_" + windowStart + "_" + bannerId, // 稳定 audienceBatch，同窗口重跑幂等
                        BannerEventType.FULL_UPDATE); // 全量更新 Banner JSON 与人群包
                scheduleRepairIds.add(bannerId); // 刷新成功，记入已修复集合
            } catch (RuntimeException exception) { // 刷新过程中任意运行时异常
                repairFailedIds.add(bannerId); // 记入失败集合，便于日志与后续排查
                log.error("Banner schedule repair failed: window={}, bannerId={}", // 打印错误日志
                        windowStart, bannerId, exception); // 带上窗口起点、bannerId 和异常堆栈
            } // try-catch 结束
        } // for 结束

        BannerAuditResult result = new BannerAuditResult( // 汇总本次对账结果快照
                windowStart, // 窗口起点（含）
                windowEnd, // 窗口终点（不含）
                dbChangedIds, // MySQL 变更 ID
                redisLatestIds, // 已对齐 ID
                scheduleRepairIds, // 成功修复 ID
                repairFailedIds); // 修复失败 ID
        logAudit(result); // 输出结构化对账日志
        return result; // 返回结果（便于单测断言）
    } // reconcileWindow 结束

    /** 将时间向下取整到最近的对账窗口起始时刻。 */
    private Long floorToWindow(Long time, Long windowMinutes) { // 例如窗口 5 分钟，把任意时刻对齐到窗口起点
        Long windowMillis = windowMinutes * 60_000L; // 窗口宽度换算为毫秒
        return Math.floorDiv(time, windowMillis) * windowMillis; // floor(time / window) * window
    } // floorToWindow 结束

    /** 输出 [BannerAudit] 对账日志，包含各集合的数量与 ID 明细。 */
    private void logAudit(BannerAuditResult result) { // 统一格式打印对账摘要
        log.info("[BannerAudit] window={}~{}, dbChanged={}, redisAlreadyLatest={}, " // 日志模板前半：窗口与计数
                        + "scheduleRepaired={}, repairFailed={}, dbChangedIds={}, " // 修复计数与 ID 列表字段
                        + "redisLatestIds={}, scheduleRepairIds={}, repairFailedIds={}", // 其余 ID 列表字段
                result.windowStart(), // 窗口起点
                result.windowEnd(), // 窗口终点
                result.dbChangedIds().size(), // MySQL 变更数量
                result.redisLatestIds().size(), // 已对齐数量
                result.scheduleRepairIds().size(), // 成功修复数量
                result.repairFailedIds().size(), // 修复失败数量
                sorted(result.dbChangedIds()), // 变更 ID 排序后输出
                sorted(result.redisLatestIds()), // 已对齐 ID 排序后输出
                sorted(result.scheduleRepairIds()), // 已修复 ID 排序后输出
                sorted(result.repairFailedIds())); // 失败 ID 排序后输出
    } // logAudit 结束

    /** 将 bannerId 集合排序后输出，便于日志阅读。 */
    private List<Long> sorted(Set<Long> ids) { // 把无序/保序 Set 转成升序 List
        return ids.stream().sorted().toList(); // 自然序排序并收集为不可变 List
    } // sorted 结束

    /** 对账结果快照，各 ID 集合在构造时转为不可变 Set。 */
    public record BannerAuditResult( // Java record：不可变对账结果
            /** 对账窗口起始时刻（含）。 */
            Long windowStart, // 窗口左边界
            /** 对账窗口结束时刻（不含）。 */
            Long windowEnd, // 窗口右边界
            /** MySQL 在该窗口内有 update_time 变更的 bannerId。 */
            Set<Long> dbChangedIds, // 库侧变更集合
            /** Redis Runtime 已与 MySQL 对齐、无需修复的 bannerId。 */
            Set<Long> redisLatestIds, // 已对齐集合
            /** 定时任务已成功修复 Redis 的 bannerId。 */
            Set<Long> scheduleRepairIds, // 成功修复集合
            /** 修复过程中抛异常的 bannerId。 */
            Set<Long> repairFailedIds) { // 修复失败集合

        /** 规范化各集合并拷贝为不可变 Set。 */
        public BannerAuditResult { // compact constructor：构造时统一规范化字段
            dbChangedIds = Set.copyOf(dbChangedIds); // 拷贝为不可变 Set，避免外部再改
            redisLatestIds = Set.copyOf(redisLatestIds); // 同上
            scheduleRepairIds = Set.copyOf(scheduleRepairIds); // 同上
            repairFailedIds = Set.copyOf(repairFailedIds); // 同上
        } // compact constructor 结束
    } // BannerAuditResult 结束
} // BannerReconciliationService 类结束
