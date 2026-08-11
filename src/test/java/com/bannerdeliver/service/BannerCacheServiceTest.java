package com.bannerdeliver.service;

import com.bannerdeliver.config.BannerProperties;
import com.bannerdeliver.domain.dto.BannerDateCacheKey;
import com.bannerdeliver.domain.dto.BannerEventType;
import com.bannerdeliver.domain.dto.BannerRuntimeDTO;
import com.bannerdeliver.domain.po.BannerInfo;
import com.bannerdeliver.utils.AudienceBucketCalculator;
import com.bannerdeliver.utils.AudienceUserListCodec;
import com.bannerdeliver.utils.BannerCacheExpiryCalculator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BannerCacheServiceTest {

    @Test
    void shouldWriteRedisAndRefreshLocalCache() throws Exception {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        HashOperations<String, Object, Object> hashOperations = mock(HashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        ObjectMapper objectMapper = new ObjectMapper();
        Cache<BannerDateCacheKey, List<BannerRuntimeDTO>> localCache =
                CacheBuilder.newBuilder().maximumSize(100).build();
        BannerCacheExpiryCalculator expiryCalculator = mock(BannerCacheExpiryCalculator.class);
        when(expiryCalculator.dateCacheExpireAt(anyLong()))
                .thenReturn(1784736000000L);
        AudienceBucketCalculator bucketCalculator = mock(AudienceBucketCalculator.class);
        BannerInfoService infoService = mock(BannerInfoService.class);
        BannerCrowdService crowdService = mock(BannerCrowdService.class);
        BannerInfo banner = BannerInfo.builder()
                .bannerId(20L)
                .productId(10L)
                .url("https://cdn.example.com/banner.png")
                .beginTime(1784512800000L)
                .endTime(1784563199000L)
                .status(1)
                .updateTime(1784511000000L)
                .build();
        when(infoService.findById(20L)).thenReturn(banner);
        when(crowdService.findByBannerId(20L)).thenReturn(List.of());
        BannerRuntimeDTO expectedRuntime = BannerRuntimeDTO.builder()
                .bannerId(20L)
                .productId(10L)
                .url(banner.getUrl())
                .beginTime(banner.getBeginTime())
                .endTime(banner.getEndTime())
                .status(1)
                .bucketCount(0)
                .audienceBatch("evt_1")
                .updateTime(banner.getUpdateTime())
                .build();
        when(hashOperations.values("product:10:date:1784476800000"))
                .thenReturn(List.of(objectMapper.writeValueAsString(expectedRuntime)));
        BannerCacheService service = new BannerCacheService(
                localCache, redisTemplate, objectMapper,
                bucketCalculator, expiryCalculator, new BannerProperties(),
                mock(AudienceUserListCodec.class), infoService, crowdService);

        service.refreshBannerCache(20L, "evt_1", BannerEventType.AUDIENCE_UPDATE);

        assertThat(localCache.getIfPresent(
                new BannerDateCacheKey(10L, 1784476800000L)))
                .containsExactly(expectedRuntime);
        verify(hashOperations).put(
                org.mockito.ArgumentMatchers.eq("product:10:date:1784476800000"),
                org.mockito.ArgumentMatchers.eq("20"),
                any(String.class));
    }

    @Test
    void shouldRequireEveryRuntimeToExistAndHaveLatestUpdateTime() throws Exception {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        HashOperations<String, Object, Object> hashOperations = mock(HashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        ObjectMapper objectMapper = new ObjectMapper();
        Cache<BannerDateCacheKey, java.util.List<BannerRuntimeDTO>> localCache =
                CacheBuilder.newBuilder().maximumSize(100).build();
        BannerCacheService service = new BannerCacheService(
                localCache,
                redisTemplate,
                objectMapper,
                mock(AudienceBucketCalculator.class),
                mock(BannerCacheExpiryCalculator.class),
                new BannerProperties(),
                mock(AudienceUserListCodec.class),
                mock(BannerInfoService.class),
                mock(BannerCrowdService.class));
        BannerInfo dbBanner = BannerInfo.builder()
                .bannerId(20L)
                .productId(10L)
                .beginTime(1784512800000L)
                .endTime(1784563199000L)
                .updateTime(1784512860000L)
                .build();
        String latestJson = objectMapper.writeValueAsString(runtime(1784512860000L));
        String staleJson = objectMapper.writeValueAsString(runtime(1784512859000L));
        when(hashOperations.get("product:10:date:1784476800000", "20"))
                .thenReturn(latestJson, staleJson, null);

        assertThat(service.hasLatestRuntime(dbBanner)).isTrue();
        assertThat(service.hasLatestRuntime(dbBanner)).isFalse();
        assertThat(service.hasLatestRuntime(dbBanner)).isFalse();
    }

    private BannerRuntimeDTO runtime(Long updateTime) {
        return BannerRuntimeDTO.builder()
                .bannerId(20L)
                .productId(10L)
                .updateTime(updateTime)
                .build();
    }
}
