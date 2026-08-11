package com.bannerdeliver.kafka.consumer;

import com.bannerdeliver.domain.dto.BannerDeliveryEvent;
import com.bannerdeliver.domain.dto.BannerEventType;
import com.bannerdeliver.service.BannerCacheService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doThrow;

class BannerEventConsumerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final BannerCacheService cacheService = mock(BannerCacheService.class);
    private final BannerEventConsumer consumer = new BannerEventConsumer(objectMapper, cacheService);

    @Test
    void shouldAcknowledgeEveryDuplicateOnlyAfterRedisStepsSucceed() throws Exception {
        BannerDeliveryEvent event = event();
        ConsumerRecord<String, String> record = record(event);
        Acknowledgment firstAcknowledgment = mock(Acknowledgment.class);
        Acknowledgment secondAcknowledgment = mock(Acknowledgment.class);
        consumer.consume(record, firstAcknowledgment);
        consumer.consume(record, secondAcknowledgment);

        var ordered = inOrder(cacheService, firstAcknowledgment, secondAcknowledgment);
        ordered.verify(cacheService).refreshBannerCache(
                20L, event.getEventId(), BannerEventType.AUDIENCE_UPDATE);
        ordered.verify(firstAcknowledgment).acknowledge();
        ordered.verify(cacheService).refreshBannerCache(
                20L, event.getEventId(), BannerEventType.AUDIENCE_UPDATE);
        ordered.verify(secondAcknowledgment).acknowledge();
    }

    @Test
    void shouldNotAcknowledgeWhenBusinessRedisRefreshFails() throws Exception {
        BannerDeliveryEvent event = event();
        Acknowledgment acknowledgment = mock(Acknowledgment.class);
        doThrow(new IllegalStateException("redis unavailable"))
                .when(cacheService).refreshBannerCache(
                        20L, event.getEventId(), BannerEventType.AUDIENCE_UPDATE);

        assertThatThrownBy(() -> consumer.consume(record(event), acknowledgment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("redis unavailable");

        verify(acknowledgment, never()).acknowledge();
    }

    private ConsumerRecord<String, String> record(BannerDeliveryEvent event) throws Exception {
        return new ConsumerRecord<>(
                "banner-delivery-event",
                0,
                1L,
                String.valueOf(event.getBannerId()),
                objectMapper.writeValueAsString(event));
    }

    private BannerDeliveryEvent event() {
        return BannerDeliveryEvent.builder()
                .eventId("evt_20260720_001")
                .bannerId(20L)
                .eventType(BannerEventType.AUDIENCE_UPDATE)
                .eventTime(1784511000000L)
                .build();
    }
}
