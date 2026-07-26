package com.bannerdeliver.utils;

import com.bannerdeliver.config.BannerProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.zip.CRC32;

/** Banner Redis Key 绝对过期时间与稳定抖动的计算。 */
@Component
@RequiredArgsConstructor
public class BannerCacheExpiryCalculator {

    private final BannerProperties properties;

    /** 计算日期 Hash 的绝对过期时刻：业务日期 + N 天的 00:00。 */
    public Instant dateCacheExpireAt(LocalDate date) {
        return date.plusDays(properties.getCache().getRedis().getExpireAfterDateDays())
                .atStartOfDay(properties.getCache().getRedis().getZoneId())
                .toInstant();
    }

    /**
     * 基于 Redis Key 的 CRC32 添加稳定抖动秒数。
     * 同一 Key 重试时抖动值不变，避免 TTL 被重复延长。
     */
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
