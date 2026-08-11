package com.bannerdeliver.utils;

import com.bannerdeliver.config.BannerProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/** Banner Redis Key 绝对过期时间与稳定抖动的计算。 */
@Component
@RequiredArgsConstructor
public class BannerCacheExpiryCalculator {

    private final BannerProperties properties;

    /** 计算日期 Hash 的绝对过期毫秒：业务日期 + N 天。 */
    public Long dateCacheExpireAt(Long date) {
        return date + properties.getCache().getRedis().getExpireAfterDateDays()
                * BannerTimeUtils.DAY_MILLIS;
    }

    /**
     * 基于 Redis Key 的 CRC32 添加稳定抖动秒数。
     * 同一 Key 重试时抖动值不变，避免 TTL 被重复延长。
     */
    public Long withStableJitter(Long baseExpireAt, String redisKey) {
        Long maxJitterSeconds = properties.getCache().getRedis().getExpireJitterMaxSeconds();
        if (maxJitterSeconds <= 0) {
            return baseExpireAt;
        }
        CRC32 crc32 = new CRC32();
        crc32.update(redisKey.getBytes(StandardCharsets.UTF_8));
        long jitterSeconds = crc32.getValue() % (maxJitterSeconds + 1L);
        return baseExpireAt + jitterSeconds * 1000L;
    }
}
