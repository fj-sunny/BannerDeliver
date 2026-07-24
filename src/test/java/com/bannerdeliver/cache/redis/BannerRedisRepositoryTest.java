package com.bannerdeliver.cache.redis;

import com.bannerdeliver.config.BannerProperties;
import com.bannerdeliver.domain.dto.BannerRuntimeDTO;
import com.bannerdeliver.domain.po.BannerInfo;
import com.bannerdeliver.utils.AudienceBucketCalculator;
import com.bannerdeliver.utils.BannerCacheExpiryCalculator;
import com.bannerdeliver.utils.BannerRedisKeyBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BannerRedisRepositoryTest {

    @Test
    void shouldRequireEveryRuntimeToExistAndHaveLatestUpdateTime() throws Exception {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        HashOperations<String, Object, Object> hashOperations = mock(HashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        ObjectMapper objectMapper = new ObjectMapper();
        BannerRedisRepository repository = new BannerRedisRepository(
                redisTemplate,
                objectMapper,
                new BannerRedisKeyBuilder(),
                mock(AudienceBucketCalculator.class),
                mock(BannerCacheExpiryCalculator.class),
                new BannerProperties());
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

        assertThat(repository.hasLatestRuntime(dbBanner)).isTrue();
        assertThat(repository.hasLatestRuntime(dbBanner)).isFalse();
        assertThat(repository.hasLatestRuntime(dbBanner)).isFalse();
    }

    private BannerRuntimeDTO runtime(String updateTime) {
        return BannerRuntimeDTO.builder()
                .bannerId(20L)
                .productId(10L)
                .updateTime(updateTime)
                .build();
    }
}
