package com.bannerdeliver.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** Banner 模块的类型化配置，绑定 application.yml 中 banner.* 前缀。 */
@Getter
@Setter
@Validated
@Component
@ConfigurationProperties(prefix = "banner")
public class BannerProperties {

    @Valid
    private Kafka kafka = new Kafka();

    @Valid
    private Audience audience = new Audience();

    @Valid
    private Cache cache = new Cache();

    @Valid
    private Reconciliation reconciliation = new Reconciliation();

    @Valid
    private Delivery delivery = new Delivery();

    /** Kafka 生产者/消费者相关配置。 */
    @Getter
    @Setter
    public static class Kafka {

        /** 缓存刷新事件 Topic 名称。 */
        @NotBlank
        private String eventTopic = "banner-delivery-event";

        /** 消费失败后重投的固定间隔（毫秒）。 */
        @Min(100)
        private Long retryBackoffMs = 1000L;
    }

    /** 人群包分桶与 Redis 批量写入配置。 */
    @Getter
    @Setter
    public static class Audience {

        /** 每个桶的目标用户数，用于计算 bucketCount。 */
        @Min(1)
        private int targetBucketSize = 50000;

        /** 单次 SADD 的最大 userId 数量。 */
        @Min(1)
        private int redisWriteBatchSize = 1000;

        /** 人群包 Redis 写入并行度；1 表示单线程。 */
        @Min(1)
        private int writeParallelism = 4;
    }

    /** 多级缓存（L1 Guava + L2 Redis）配置。 */
    @Getter
    @Setter
    public static class Cache {

        @Valid
        private Local local = new Local();

        @Valid
        private Redis redis = new Redis();
    }

    /** Guava 本地缓存 L1 配置。 */
    @Getter
    @Setter
    public static class Local {

        /** 写入后过期时间（秒）。 */
        @Min(1)
        private Long expireAfterWriteSeconds = 30L;

        /** 最大缓存条目数。 */
        @Min(1)
        private long maximumSize = 10000;
    }

    /** Redis L2 缓存与兜底查询配置。 */
    @Getter
    @Setter
    public static class Redis {

        /** 当天未命中时，向前回溯的业务天数（0 表示不兜底）。 */
        @Min(0)
        private Long fallbackDays = 1L;

        /** 日期 Hash 在业务日期之后第 N 天 00:00 过期。 */
        @Min(1)
        private Long expireAfterDateDays = 2L;

        /** TTL 稳定抖动上限（秒），同一 Key 抖动值固定。 */
        @Min(0)
        private Long expireJitterMaxSeconds = 300L;

        /** 业务时区相对 UTC 的固定偏移毫秒，默认 UTC+8。 */
        private Long zoneOffsetMillis = 28_800_000L;
    }

    /** 定时对账任务配置。 */
    @Getter
    @Setter
    public static class Reconciliation {

        /** 是否启用定时对账。 */
        private boolean enabled = true;

        /** 对账窗口宽度（分钟）。 */
        @Min(1)
        private Long windowMinutes = 5L;

        /** 定时任务 cron 表达式。 */
        @NotBlank
        private String cron = "0 */5 * * * *";
    }

    /** 用户查询兜底配置。 */
    @Getter
    @Setter
    public static class Delivery {

        @Valid
        private DefaultBanner defaultBanner = new DefaultBanner();
    }

    /** 静态默认 Banner，当天和历史均未命中时使用。 */
    @Getter
    @Setter
    public static class DefaultBanner {

        /** 兜底 Banner ID。 */
        @NotNull
        @PositiveOrZero
        private Long bannerId = 0L;

        /** 兜底 Banner 图片 URL。 */
        @NotBlank
        private String url = "https://cdn.example.com/default-banner.png";
    }
}
