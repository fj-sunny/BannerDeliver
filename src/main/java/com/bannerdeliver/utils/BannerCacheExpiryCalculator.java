package com.bannerdeliver.utils;

import com.bannerdeliver.config.BannerProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.zip.CRC32;

@Component
@RequiredArgsConstructor
public class BannerCacheExpiryCalculator {

    private final BannerProperties properties;

    public Instant dateCacheExpireAt(LocalDate date) {
        return date.plusDays(properties.getCache().getRedis().getExpireAfterDateDays())
                .atStartOfDay(properties.getCache().getRedis().getZoneId())
                .toInstant();
    }

    public Instant withStableJitter(Instant baseExpireAt, String redisKey) {
        int maxJitterSeconds = properties.getCache().getRedis().getExpireJitterMaxSeconds();
        if (maxJitterSeconds <= 0) {
            return baseExpireAt;
        }
        CRC32 crc32 = new CRC32();
        crc32.update(redisKey.getBytes(StandardCharsets.UTF_8));
        long jitterSeconds = crc32.getValue() % (maxJitterSeconds + 1L);
        return baseExpireAt.plusSeconds(jitterSeconds);
    }
}
