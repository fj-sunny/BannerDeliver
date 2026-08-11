package com.bannerdeliver.utils;

import com.bannerdeliver.config.BannerProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * Banner Redis Key 绝对过期时间与稳定抖动的计算。
 *
 * <p>人群包 Set 的过期规则：
 * <ol>
 *   <li>基准 = 该 Banner 投放期内所有日期 Hash 过期点的最大值
 *       （每个业务日 {@code date + expireAfterDateDays}，默认 +2 天）。</li>
 *   <li>再经 {@link #withStableJitter} 按 Key 加 0～{@code expireJitterMaxSeconds} 秒抖动。</li>
 *   <li>切换新 {@code audienceBatch} 时不会修改旧 batch Key 的 TTL；旧 Key 按写入时的过期点自然消失。</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class BannerCacheExpiryCalculator {

    private final BannerProperties properties;

    /** 计算日期 Hash 的绝对过期毫秒：业务日期 + N 天（默认 N=2）。 */
    public Long dateCacheExpireAt(Long date) {
        return date + properties.getCache().getRedis().getExpireAfterDateDays()
                * BannerTimeUtils.DAY_MILLIS;
    }

    /**
     * 基于 Redis Key 的 CRC32 添加稳定抖动秒数。
     * 同一 Key 重试时抖动值不变，避免 TTL 被重复拉长；新旧 audienceBatch 因 Key 不同而抖动独立。
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
