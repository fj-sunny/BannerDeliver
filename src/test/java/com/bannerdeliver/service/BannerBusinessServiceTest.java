package com.bannerdeliver.service;

import com.bannerdeliver.domain.po.BannerCrowd;
import com.bannerdeliver.domain.po.BannerInfo;
import com.bannerdeliver.mapper.BannerCrowdMapper;
import com.bannerdeliver.mapper.BannerInfoMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BannerBusinessServiceTest {

    @Test
    void shouldFindBannerById() {
        BannerInfoMapper mapper = mock(BannerInfoMapper.class);
        BannerInfo current = BannerInfo.builder()
                .bannerId(20L)
                .productId(10L)
                .build();
        when(mapper.selectById(20L)).thenReturn(current);
        BannerInfoService service = new BannerInfoService(mapper);

        assertThat(service.findById(20L)).isEqualTo(current);
        verify(mapper).selectById(20L);
    }

    @Test
    void shouldFindCrowdsByBannerId() {
        BannerCrowdMapper crowdMapper = mock(BannerCrowdMapper.class);
        List<BannerCrowd> crowds = List.of(
                BannerCrowd.builder()
                        .bannerId(20L)
                        .pageNum(0)
                        .userList("[\"1001\",\"1002\"]")
                        .build());
        when(crowdMapper.selectList(any())).thenReturn(crowds);
        BannerCrowdService service = new BannerCrowdService(crowdMapper);

        assertThat(service.findByBannerId(20L)).isEqualTo(crowds);
        verify(crowdMapper).selectList(any());
    }
}
