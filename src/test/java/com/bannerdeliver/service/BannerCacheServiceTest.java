package com.bannerdeliver.service;

import com.bannerdeliver.config.BannerProperties;
import com.bannerdeliver.domain.dto.BannerDateCacheKey;
import com.bannerdeliver.domain.dto.BannerRuntimeDTO;
import com.bannerdeliver.domain.po.BannerInfo;
import com.bannerdeliver.utils.AudienceBucketCalculator;
import com.bannerdeliver.utils.AudienceUserListCodec;
import com.bannerdeliver.utils.BannerCacheExpiryCalculator;
import com.bannerdeliver.utils.BannerRedisKeyBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BannerCacheServiceTest {

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
                new BannerRedisKeyBuilder(),
                mock(AudienceBucketCalculator.class),
                mock(BannerCacheExpiryCalculator.class),
                new BannerProperties(),
                mock(AudienceUserListCodec.class),
                mock(BannerInfoService.class),
                mock(BannerCrowdService.class));
        BannerInfo dbBanner = BannerInfo.builder()
                .bannerId(20L)
                .productId(10L)
                .beginTime("2026-07-20 10:00:00")
                .endTime("2026-07-20 23:59:59")
                .updateTime("2026-07-20 10:01:00")
                .build();
        String latestJson = objectMapper.writeValueAsString(runtime("2026-07-20 10:01:00"));
        String staleJson = objectMapper.writeValueAsString(runtime("2026-07-20 10:00:59"));
        when(hashOperations.get("product:10:date:20260720", "20"))
                .thenReturn(latestJson, staleJson, null);

        assertThat(service.hasLatestRuntime(dbBanner)).isTrue();
        assertThat(service.hasLatestRuntime(dbBanner)).isFalse();
        assertThat(service.hasLatestRuntime(dbBanner)).isFalse();
    }

    private BannerRuntimeDTO runtime(String updateTime) {
        return BannerRuntimeDTO.builder()
                .bannerId(20L)
                .productId(10L)
                .updateTime(updateTime)
                .build();
    }
}
