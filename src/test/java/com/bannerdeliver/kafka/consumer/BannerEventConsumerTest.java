package com.bannerdeliver.kafka.consumer;

import com.bannerdeliver.domain.dto.BannerDeliveryEvent;
import com.bannerdeliver.service.BannerCacheService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BannerEventConsumerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
    private final BannerCacheService cacheService = mock(BannerCacheService.class);
    private final BannerEventConsumer consumer = new BannerEventConsumer(
            objectMapper, validator, cacheService);

    @Test
    void shouldAcknowledgeEveryDuplicateOnlyAfterRedisStepsSucceed() throws Exception {
        BannerDeliveryEvent event = event();
        ConsumerRecord<String, String> record = record(event);
        Acknowledgment firstAcknowledgment = mock(Acknowledgment.class);
        Acknowledgment secondAcknowledgment = mock(Acknowledgment.class);
        when(cacheService.refreshFromMysql(any(BannerDeliveryEvent.class)))
                .thenReturn(BannerCacheService.RefreshResult.REFRESHED)
                .thenReturn(BannerCacheService.RefreshResult.REFRESHED);

        consumer.consume(record, firstAcknowledgment);
        consumer.consume(record, secondAcknowledgment);

        var ordered = inOrder(cacheService, firstAcknowledgment, secondAcknowledgment);
        ordered.verify(cacheService).refreshFromMysql(event);
        ordered.verify(cacheService).recordConsumeSuccess(20L, event.getEventTime());
        ordered.verify(firstAcknowledgment).acknowledge();
        ordered.verify(cacheService).refreshFromMysql(event);
        ordered.verify(cacheService).recordConsumeSuccess(20L, event.getEventTime());
        ordered.verify(secondAcknowledgment).acknowledge();
        verify(cacheService, times(2)).refreshFromMysql(event);
    }

    @Test
    void shouldNotAcknowledgeWhenBusinessRedisRefreshFails() throws Exception {
        BannerDeliveryEvent event = event();
        Acknowledgment acknowledgment = mock(Acknowledgment.class);
        when(cacheService.refreshFromMysql(any(BannerDeliveryEvent.class)))
                .thenThrow(new IllegalStateException("redis unavailable"));

        assertThatThrownBy(() -> consumer.consume(record(event), acknowledgment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("redis unavailable");

        verify(cacheService, never()).recordConsumeSuccess(any(), any());
        verify(acknowledgment, never()).acknowledge();
    }

    @Test
    void shouldNotAcknowledgeWhenConsumeWindowRecordFails() throws Exception {
        BannerDeliveryEvent event = event();
        Acknowledgment acknowledgment = mock(Acknowledgment.class);
        when(cacheService.refreshFromMysql(any(BannerDeliveryEvent.class)))
                .thenReturn(BannerCacheService.RefreshResult.REFRESHED);
        org.mockito.Mockito.doThrow(new IllegalStateException("consume record failed"))
                .when(cacheService)
                .recordConsumeSuccess(20L, event.getEventTime());

        assertThatThrownBy(() -> consumer.consume(record(event), acknowledgment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("consume record failed");

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
                .eventType(com.bannerdeliver.domain.dto.BannerEventType.AUDIENCE_UPDATE)
                .bannerId(20L)
                .productId(10L)
                .oldProductId(10L)
                .oldBeginTime("2026-07-20 10:00:00")
                .oldEndTime("2026-07-20 23:59:59")
                .eventTime("2026-07-20 09:30:00")
                .build();
    }
}
