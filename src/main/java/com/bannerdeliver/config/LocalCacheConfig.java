package com.bannerdeliver.config;

import com.bannerdeliver.domain.dto.BannerDateCacheKey;
import com.bannerdeliver.domain.dto.BannerRuntimeDTO;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

/** Guava 本地缓存（L1）的 Spring Bean 配置。 */
@Configuration
public class LocalCacheConfig {

    /** 创建 Banner Runtime 的 Guava 本地缓存，TTL 和容量由配置项驱动。 */
    @Bean
    public Cache<BannerDateCacheKey, List<BannerRuntimeDTO>> bannerRuntimeLocalCache(
            BannerProperties properties) {
        BannerProperties.Local local = properties.getCache().getLocal();
        return CacheBuilder.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(local.getExpireAfterWriteSeconds()))
                .maximumSize(local.getMaximumSize())
                .build();
    }
}
