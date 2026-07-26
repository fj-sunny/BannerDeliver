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

import java.time.ZoneId;

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

    @Getter
    @Setter
    public static class Kafka {

        @NotBlank
        private String eventTopic = "banner-delivery-event";

        @Min(100)
        private long retryBackoffMs = 1000;
    }

    @Getter
    @Setter
    public static class Audience {

        @Min(1)
        private int targetBucketSize = 50000;

        @Min(1)
        private int redisWriteBatchSize = 1000;
    }

    @Getter
    @Setter
    public static class Cache {

        @Valid
        private Local local = new Local();

        @Valid
        private Redis redis = new Redis();
    }

    @Getter
    @Setter
    public static class Local {

        @Min(1)
        private int expireAfterWriteSeconds = 30;

        @Min(1)
        private long maximumSize = 10000;
    }

    @Getter
    @Setter
    public static class Redis {

        @Min(0)
        private int fallbackDays = 1;

        @Min(1)
        private int expireAfterDateDays = 2;

        @Min(0)
        private int expireJitterMaxSeconds = 300;

        private ZoneId zoneId = ZoneId.of("Asia/Shanghai");
    }

    @Getter
    @Setter
    public static class Reconciliation {

        private boolean enabled = true;

        @Min(1)
        private int windowMinutes = 5;

        @NotBlank
        private String cron = "0 */5 * * * *";
    }

    @Getter
    @Setter
    public static class Delivery {

        @Valid
        private DefaultBanner defaultBanner = new DefaultBanner();
    }

    @Getter
    @Setter
    public static class DefaultBanner {

        @NotNull
        @PositiveOrZero
        private Long bannerId = 0L;

        @NotBlank
        private String url = "https://cdn.example.com/default-banner.png";
    }
}
