package com.bannerdeliver.config;

import com.bannerdeliver.domain.dto.BannerDateCacheKey;
import com.bannerdeliver.domain.dto.BannerRuntimeDTO;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

@Configuration
public class LocalCacheConfig {

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
