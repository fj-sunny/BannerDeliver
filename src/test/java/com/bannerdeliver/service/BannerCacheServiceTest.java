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
import com.google.common.util.concurrent.MoreExecutors;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BannerCacheServiceTest {

    private static final long ZONE = 28_800_000L;
    private static final long DAY = 86_400_000L;
    private static final long BEGIN = 1_784_512_800_000L;
    private static final long END_DAY1 = 1_784_563_199_000L;
    private static final long END_DAY2 = END_DAY1 + DAY;
    private static final long DATE0 = 1_784_476_800_000L;

    @Test
    void shouldWriteRedisAndInvalidateLocalCache() throws Exception {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        HashOperations<String, Object, Object> hashOperations = mock(HashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        ObjectMapper objectMapper = new ObjectMapper();
        Cache<BannerDateCacheKey, List<BannerRuntimeDTO>> localCache =
                CacheBuilder.newBuilder().maximumSize(100).build();
        BannerDateCacheKey cacheKey = new BannerDateCacheKey(10L, DATE0);
        localCache.put(cacheKey, List.of(BannerRuntimeDTO.builder()
                .bannerId(20L)
                .productId(10L)
                .audienceBatch("evt_old")
                .build()));
        BannerCacheExpiryCalculator expiryCalculator = mock(BannerCacheExpiryCalculator.class);
        when(expiryCalculator.dateCacheExpireAt(anyLong()))
                .thenReturn(1_784_736_000_000L);
        AudienceBucketCalculator bucketCalculator = mock(AudienceBucketCalculator.class);
        BannerInfoService infoService = mock(BannerInfoService.class);
        BannerCrowdService crowdService = mock(BannerCrowdService.class);
        BannerInfo banner = BannerInfo.builder()
                .bannerId(20L)
                .productId(10L)
                .url("https://cdn.example.com/banner.png")
                .beginTime(BEGIN)
                .endTime(END_DAY1)
                .status(1)
                .updateTime(1_784_511_000_000L)
                .build();
        when(infoService.findById(20L)).thenReturn(banner);
        when(crowdService.findByBannerId(20L)).thenReturn(List.of());
        BannerCacheService service = newService(
                localCache, redisTemplate, objectMapper,
                bucketCalculator, expiryCalculator, infoService, crowdService);

        service.refreshBannerCache(20L, "evt_1", BannerEventType.AUDIENCE_UPDATE);

        assertThat(localCache.getIfPresent(cacheKey)).isNull();
        verify(hashOperations).put(
                eq("product:10:date:" + DATE0),
                eq("20"),
                any(String.class));
    }

    @Test
    void shouldRequireEveryRuntimeToExistAndHaveLatestUpdateTime() throws Exception {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        HashOperations<String, Object, Object> hashOperations = mock(HashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        ObjectMapper objectMapper = new ObjectMapper();
        Cache<BannerDateCacheKey, List<BannerRuntimeDTO>> localCache =
                CacheBuilder.newBuilder().maximumSize(100).build();
        BannerCacheService service = newService(
                localCache,
                redisTemplate,
                objectMapper,
                mock(AudienceBucketCalculator.class),
                mock(BannerCacheExpiryCalculator.class),
                mock(BannerInfoService.class),
                mock(BannerCrowdService.class));
        BannerInfo dbBanner = BannerInfo.builder()
                .bannerId(20L)
                .productId(10L)
                .beginTime(BEGIN)
                .endTime(END_DAY1)
                .updateTime(1_784_512_860_000L)
                .build();
        String latestJson = objectMapper.writeValueAsString(runtime(1_784_512_860_000L));
        String staleJson = objectMapper.writeValueAsString(runtime(1_784_512_859_000L));
        when(hashOperations.get("product:10:date:" + DATE0, "20"))
                .thenReturn(latestJson, staleJson, null);

        assertThat(service.hasLatestRuntime(dbBanner)).isTrue();
        assertThat(service.hasLatestRuntime(dbBanner)).isFalse();
        assertThat(service.hasLatestRuntime(dbBanner)).isFalse();
    }

    @Test
    void shouldSkipAudienceTtlRenewWhenBannerUpdateDoesNotExtendExpiry() throws Exception {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        HashOperations<String, Object, Object> hashOperations = mock(HashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        ObjectMapper objectMapper = new ObjectMapper();
        BannerCacheExpiryCalculator expiryCalculator = mock(BannerCacheExpiryCalculator.class);
        when(expiryCalculator.dateCacheExpireAt(DATE0)).thenReturn(DATE0 + 2 * DAY);
        when(expiryCalculator.withStableJitter(anyLong(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        BannerInfoService infoService = mock(BannerInfoService.class);
        BannerInfo banner = BannerInfo.builder()
                .bannerId(20L)
                .productId(10L)
                .url("https://cdn/new.png")
                .beginTime(BEGIN)
                .endTime(END_DAY1)
                .status(1)
                .updateTime(1_784_512_900_000L)
                .build();
        when(infoService.findById(20L)).thenReturn(banner);
        BannerRuntimeDTO existing = BannerRuntimeDTO.builder()
                .bannerId(20L)
                .productId(10L)
                .beginTime(BEGIN)
                .endTime(END_DAY1)
                .bucketCount(2)
                .audienceBatch("evt_old")
                .updateTime(1_784_511_000_000L)
                .build();
        when(hashOperations.get("product:10:date:" + DATE0, "20"))
                .thenReturn(objectMapper.writeValueAsString(existing));
        BannerCacheService service = newService(
                CacheBuilder.<BannerDateCacheKey, List<BannerRuntimeDTO>>newBuilder()
                        .maximumSize(100).build(),
                redisTemplate,
                objectMapper,
                mock(AudienceBucketCalculator.class),
                expiryCalculator,
                infoService,
                mock(BannerCrowdService.class));

        service.refreshBannerCache(20L, "evt_banner", BannerEventType.BANNER_UPDATE);

        verify(redisTemplate, never()).expire(
                org.mockito.ArgumentMatchers.startsWith("banner:audience:"),
                anyLong(),
                any(TimeUnit.class));
        verify(hashOperations).put(eq("product:10:date:" + DATE0), eq("20"), any(String.class));
    }

    @Test
    void shouldRenewAudienceTtlWhenBannerUpdateExtendsExpiry() throws Exception {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        HashOperations<String, Object, Object> hashOperations = mock(HashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(redisTemplate.expire(anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        ObjectMapper objectMapper = new ObjectMapper();
        BannerCacheExpiryCalculator expiryCalculator = mock(BannerCacheExpiryCalculator.class);
        long expiryDay0 = DATE0 + 2 * DAY;
        long expiryDay1 = DATE0 + DAY + 2 * DAY;
        when(expiryCalculator.dateCacheExpireAt(DATE0)).thenReturn(expiryDay0);
        when(expiryCalculator.dateCacheExpireAt(DATE0 + DAY)).thenReturn(expiryDay1);
        when(expiryCalculator.withStableJitter(anyLong(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        BannerInfoService infoService = mock(BannerInfoService.class);
        BannerInfo banner = BannerInfo.builder()
                .bannerId(20L)
                .productId(10L)
                .url("https://cdn/new.png")
                .beginTime(BEGIN)
                .endTime(END_DAY2)
                .status(1)
                .updateTime(1_784_512_900_000L)
                .build();
        when(infoService.findById(20L)).thenReturn(banner);
        BannerRuntimeDTO existing = BannerRuntimeDTO.builder()
                .bannerId(20L)
                .productId(10L)
                .beginTime(BEGIN)
                .endTime(END_DAY1)
                .bucketCount(2)
                .audienceBatch("evt_old")
                .updateTime(1_784_511_000_000L)
                .build();
        when(hashOperations.get(anyString(), eq("20")))
                .thenReturn(objectMapper.writeValueAsString(existing));
        BannerCacheService service = newService(
                CacheBuilder.<BannerDateCacheKey, List<BannerRuntimeDTO>>newBuilder()
                        .maximumSize(100).build(),
                redisTemplate,
                objectMapper,
                mock(AudienceBucketCalculator.class),
                expiryCalculator,
                infoService,
                mock(BannerCrowdService.class));

        service.refreshBannerCache(20L, "evt_banner", BannerEventType.BANNER_UPDATE);

        verify(redisTemplate).expire(
                eq("banner:audience:20:evt_old:bucket:0"), anyLong(), eq(TimeUnit.MILLISECONDS));
        verify(redisTemplate).expire(
                eq("banner:audience:20:evt_old:bucket:1"), anyLong(), eq(TimeUnit.MILLISECONDS));
    }

    private BannerCacheService newService(
            Cache<BannerDateCacheKey, List<BannerRuntimeDTO>> localCache,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            AudienceBucketCalculator bucketCalculator,
            BannerCacheExpiryCalculator expiryCalculator,
            BannerInfoService infoService,
            BannerCrowdService crowdService) {
        BannerProperties properties = new BannerProperties();
        properties.getCache().getRedis().setZoneOffsetMillis(ZONE);
        properties.getAudience().setWriteParallelism(1);
        return new BannerCacheService(
                localCache,
                redisTemplate,
                objectMapper,
                bucketCalculator,
                expiryCalculator,
                properties,
                mock(AudienceUserListCodec.class),
                infoService,
                crowdService,
                MoreExecutors.newDirectExecutorService());
    }

    private BannerRuntimeDTO runtime(Long updateTime) {
        return BannerRuntimeDTO.builder()
                .bannerId(20L)
                .productId(10L)
                .updateTime(updateTime)
                .build();
    }
}
