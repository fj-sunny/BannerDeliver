package com.bannerdeliver.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.bannerdeliver.domain.dto.BannerDeliveryEvent;
import com.bannerdeliver.domain.dto.BannerEventType;
import com.bannerdeliver.domain.po.BannerCrowd;
import com.bannerdeliver.domain.po.BannerInfo;
import com.bannerdeliver.kafka.producer.BannerEventProducer;
import com.bannerdeliver.mapper.BannerCrowdMapper;
import com.bannerdeliver.mapper.BannerInfoMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BannerBusinessServiceTest {

    @Test
    void shouldUpdateBannerAndPublishEventAfterCommit() {
        BannerInfoMapper mapper = mock(BannerInfoMapper.class);
        BannerEventProducer producer = mock(BannerEventProducer.class);
        BannerInfo current = BannerInfo.builder()
                .bannerId(20L)
                .productId(10L)
                .beginTime(1784512800000L)
                .endTime(1784563199000L)
                .build();
        when(mapper.selectById(20L)).thenReturn(current);
        when(mapper.updateById(any(BannerInfo.class))).thenReturn(1);
        BannerInfoService service = new BannerInfoService(mapper, producer);
        BannerInfo update = BannerInfo.builder()
                .bannerId(20L)
                .productId(11L)
                .url("https://cdn/new.png")
                .build();

        Long before = System.currentTimeMillis();
        service.updateBanner(update);
        Long after = System.currentTimeMillis();

        assertThat(update.getUpdateTime()).isBetween(before, after);
        ArgumentCaptor<BannerDeliveryEvent> eventCaptor =
                ArgumentCaptor.forClass(BannerDeliveryEvent.class);
        verify(producer).sendAfterCommit(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getBannerId()).isEqualTo(20L);
        assertThat(eventCaptor.getValue().getEventType())
                .isEqualTo(BannerEventType.BANNER_UPDATE);
        assertThat(eventCaptor.getValue().getEventTime()).isEqualTo(update.getUpdateTime());
    }

    @Test
    void shouldReplaceCrowdUpdateBannerVersionAndPublishAudienceEvent() {
        BannerCrowdMapper crowdMapper = mock(BannerCrowdMapper.class);
        BannerInfoMapper infoMapper = mock(BannerInfoMapper.class);
        BannerEventProducer producer = mock(BannerEventProducer.class);
        when(infoMapper.selectById(20L)).thenReturn(BannerInfo.builder()
                .bannerId(20L)
                .productId(10L)
                .build());
        when(infoMapper.updateById(any(BannerInfo.class))).thenReturn(1);
        when(crowdMapper.insert(any(BannerCrowd.class))).thenReturn(1);
        BannerCrowdService service = new BannerCrowdService(
                crowdMapper, infoMapper, producer);
        List<BannerCrowd> crowds = List.of(
                BannerCrowd.builder()
                        .pageNum(0)
                        .userList("[\"1001\",\"1002\"]")
                        .build(),
                BannerCrowd.builder()
                        .pageNum(1)
                        .userList("[\"1003\"]")
                        .build());

        service.replaceCrowd(20L, crowds);

        verify(crowdMapper).delete(
                org.mockito.ArgumentMatchers.<Wrapper<BannerCrowd>>any());
        ArgumentCaptor<BannerCrowd> crowdCaptor = ArgumentCaptor.forClass(BannerCrowd.class);
        verify(crowdMapper, org.mockito.Mockito.times(2)).insert(crowdCaptor.capture());
        assertThat(crowdCaptor.getAllValues())
                .extracting(BannerCrowd::getBannerId)
                .containsOnly(20L);
        assertThat(crowdCaptor.getAllValues())
                .extracting(BannerCrowd::getUpdateTime)
                .containsOnly(crowdCaptor.getAllValues().get(0).getUpdateTime());
        ArgumentCaptor<BannerDeliveryEvent> eventCaptor =
                ArgumentCaptor.forClass(BannerDeliveryEvent.class);
        verify(producer).sendAfterCommit(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getBannerId()).isEqualTo(20L);
        assertThat(eventCaptor.getValue().getEventType())
                .isEqualTo(BannerEventType.AUDIENCE_UPDATE);
        assertThat(eventCaptor.getValue().getEventTime())
                .isEqualTo(crowdCaptor.getAllValues().get(0).getUpdateTime());
    }
}
