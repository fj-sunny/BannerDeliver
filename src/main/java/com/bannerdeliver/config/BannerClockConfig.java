package com.bannerdeliver.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** 业务时钟配置，保证查询和对账使用统一时区。 */
@Configuration
public class BannerClockConfig {

    /** 提供与 Banner 缓存配置一致的系统时钟，便于测试时替换。 */
    @Bean
    public Clock bannerClock(BannerProperties properties) {
        return Clock.system(properties.getCache().getRedis().getZoneId());
    }
}
