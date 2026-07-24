package com.bannerdeliver.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class BannerClockConfig {

    @Bean
    public Clock bannerClock(BannerProperties properties) {
        return Clock.system(properties.getCache().getRedis().getZoneId());
    }
}
