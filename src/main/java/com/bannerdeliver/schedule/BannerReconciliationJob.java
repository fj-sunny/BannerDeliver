package com.bannerdeliver.schedule;

import com.bannerdeliver.config.BannerProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Banner 缓存对账定时任务入口。
 *
 * <p>默认每五分钟触发，只负责计算当前时间并调用对账服务，不在 Job 中堆叠业务逻辑。</p>
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "banner.reconciliation",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class BannerReconciliationJob {

    private final BannerReconciliationService reconciliationService;
    private final BannerProperties properties;

    @Scheduled(
            cron = "${banner.reconciliation.cron:0 */5 * * * *}",
            zone = "${banner.cache.redis.zone-id:Asia/Shanghai}")
    public void reconcile() {
        reconciliationService.reconcilePreviousCompleteWindow(
                LocalDateTime.now(properties.getCache().getRedis().getZoneId()));
    }
}
