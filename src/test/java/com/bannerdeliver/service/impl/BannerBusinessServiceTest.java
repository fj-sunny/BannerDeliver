package com.bannerdeliver.service.impl;

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

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BannerBusinessServiceTest {

    private final Clock clock = Clock.fixed(
            Instant.parse("2026-07-20T01:30:00Z"),
            ZoneId.of("Asia/Shanghai"));

    @Test
    void shouldUpdateBannerAndPublishEventAfterCommit() {
        BannerInfoMapper mapper = mock(BannerInfoMapper.class);
        BannerEventProducer producer = mock(BannerEventProducer.class);
        BannerInfo current = BannerInfo.builder()
                .bannerId(20L)
                .productId(10L)
                .build();
        when(mapper.selectById(20L)).thenReturn(current);
        when(mapper.updateById(any(BannerInfo.class))).thenReturn(1);
        BannerInfoServiceImpl service = new BannerInfoServiceImpl(mapper, producer, clock);
        BannerInfo update = BannerInfo.builder()
                .bannerId(20L)
                .productId(11L)
                .url("https://cdn/new.png")
                .build();

        service.updateBanner(update, List.of("product_id", "url"));

        assertThat(update.getUpdateTime()).isEqualTo("2026-07-20 09:30:00");
        ArgumentCaptor<BannerDeliveryEvent> eventCaptor =
                ArgumentCaptor.forClass(BannerDeliveryEvent.class);
        verify(producer).sendAfterCommit(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getEventType())
                .isEqualTo(BannerEventType.BANNER_UPDATE);
        assertThat(eventCaptor.getValue().getProductId()).isEqualTo(11L);
        assertThat(eventCaptor.getValue().getOldProductId()).isEqualTo(10L);
        assertThat(eventCaptor.getValue().getChangedFields())
                .containsExactly("product_id", "url");
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
        BannerCrowdServiceImpl service = new BannerCrowdServiceImpl(
                crowdMapper, infoMapper, producer, clock);
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
                .containsOnly("2026-07-20 09:30:00");
        ArgumentCaptor<BannerDeliveryEvent> eventCaptor =
                ArgumentCaptor.forClass(BannerDeliveryEvent.class);
        verify(producer).sendAfterCommit(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getEventType())
                .isEqualTo(BannerEventType.AUDIENCE_UPDATE);
        assertThat(eventCaptor.getValue().getChangedFields())
                .containsExactly("user_list");
    }
}
