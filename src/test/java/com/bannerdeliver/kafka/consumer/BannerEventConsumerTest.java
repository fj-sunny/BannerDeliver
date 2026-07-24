package com.bannerdeliver.kafka.consumer;

import com.bannerdeliver.cache.redis.BannerRedisRepository;
import com.bannerdeliver.domain.dto.BannerDeliveryEvent;
import com.bannerdeliver.service.BannerCacheRefreshService;
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
    private final BannerCacheRefreshService refreshService =
            mock(BannerCacheRefreshService.class);
    private final BannerRedisRepository redisRepository = mock(BannerRedisRepository.class);
    private final BannerEventConsumer consumer = new BannerEventConsumer(
            objectMapper, validator, refreshService, redisRepository);

    @Test
    void shouldAcknowledgeEveryDuplicateOnlyAfterRedisStepsSucceed() throws Exception {
        BannerDeliveryEvent event = event();
        ConsumerRecord<String, String> record = record(event);
        Acknowledgment firstAcknowledgment = mock(Acknowledgment.class);
        Acknowledgment secondAcknowledgment = mock(Acknowledgment.class);
        when(refreshService.refreshFromMysql(any(BannerDeliveryEvent.class)))
                .thenReturn(BannerCacheRefreshService.RefreshResult.REFRESHED)
                .thenReturn(BannerCacheRefreshService.RefreshResult.SKIPPED_SAME_OR_OLDER_VERSION);

        consumer.consume(record, firstAcknowledgment);
        consumer.consume(record, secondAcknowledgment);

        var ordered = inOrder(refreshService, redisRepository,
                firstAcknowledgment, secondAcknowledgment);
        ordered.verify(refreshService).refreshFromMysql(event);
        ordered.verify(redisRepository).recordConsumeSuccess(20L, event.getEventTime());
        ordered.verify(firstAcknowledgment).acknowledge();
        ordered.verify(refreshService).refreshFromMysql(event);
        ordered.verify(redisRepository).recordConsumeSuccess(20L, event.getEventTime());
        ordered.verify(secondAcknowledgment).acknowledge();
        verify(refreshService, times(2)).refreshFromMysql(event);
    }

    @Test
    void shouldNotAcknowledgeWhenBusinessRedisRefreshFails() throws Exception {
        BannerDeliveryEvent event = event();
        Acknowledgment acknowledgment = mock(Acknowledgment.class);
        when(refreshService.refreshFromMysql(any(BannerDeliveryEvent.class)))
                .thenThrow(new IllegalStateException("redis unavailable"));

        assertThatThrownBy(() -> consumer.consume(record(event), acknowledgment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("redis unavailable");

        verify(redisRepository, never()).recordConsumeSuccess(any(), any());
        verify(acknowledgment, never()).acknowledge();
    }

    @Test
    void shouldNotAcknowledgeWhenConsumeWindowRecordFails() throws Exception {
        BannerDeliveryEvent event = event();
        Acknowledgment acknowledgment = mock(Acknowledgment.class);
        when(refreshService.refreshFromMysql(any(BannerDeliveryEvent.class)))
                .thenReturn(BannerCacheRefreshService.RefreshResult.REFRESHED);
        org.mockito.Mockito.doThrow(new IllegalStateException("consume record failed"))
                .when(redisRepository)
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
                .eventTime("2026-07-20 09:30:00")
                .build();
    }
}
