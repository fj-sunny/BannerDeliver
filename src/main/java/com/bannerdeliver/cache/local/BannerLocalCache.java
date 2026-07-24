package com.bannerdeliver.cache.local;

import com.bannerdeliver.cache.BannerRuntimeCache;
import com.bannerdeliver.domain.dto.BannerDateCacheKey;
import com.bannerdeliver.domain.dto.BannerRuntimeDTO;
import com.google.common.cache.Cache;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * Guava 本地缓存。
 *
 * <p>只缓存体量较小的 Banner Runtime 列表，不缓存人群包。缓存未命中时通过
 * {@link BannerRuntimeCache} 回源 Redis；Kafka 刷新和定时修复成功后主动失效相关 Key。</p>
 */
@Component
@RequiredArgsConstructor
public class BannerLocalCache {

    private final Cache<BannerDateCacheKey, List<BannerRuntimeDTO>> localCache;
    private final BannerRuntimeCache runtimeCache;

    /**
     * 查询本机缓存；同一个 Key 并发未命中时，Guava 只执行一次 Redis 回源加载。
     */
    public List<BannerRuntimeDTO> getBanners(Long productId, LocalDate date) {
        BannerDateCacheKey cacheKey = new BannerDateCacheKey(productId, date);
        try {
            return localCache.get(cacheKey, () -> List.copyOf(
                    runtimeCache.findBanners(productId, date)));
        } catch (ExecutionException exception) {
            throw new IllegalStateException(
                    "Unable to load banner runtime cache for " + cacheKey,
                    exception.getCause());
        }
    }

    public void invalidate(Long productId, LocalDate date) {
        localCache.invalidate(new BannerDateCacheKey(productId, date));
    }

    /** 批量失效 Redis 刷新所影响的旧、新 productId + date。 */
    public void invalidateAll(Collection<BannerDateCacheKey> cacheKeys) {
        if (!cacheKeys.isEmpty()) {
            localCache.invalidateAll(cacheKeys);
        }
    }
}
