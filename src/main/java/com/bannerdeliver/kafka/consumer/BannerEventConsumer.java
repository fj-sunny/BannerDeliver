package com.bannerdeliver.kafka.consumer;

import com.bannerdeliver.cache.redis.BannerRedisRepository;
import com.bannerdeliver.domain.dto.BannerDeliveryEvent;
import com.bannerdeliver.service.BannerCacheRefreshService;
import com.bannerdeliver.utils.BannerDateTimeUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Kafka 消费者。
 *
 * <p>消息只用于定位 Banner，业务内容始终重新查询 MySQL。只有业务 Redis、
 * LocalCache 和消费窗口记录全部成功后才手动确认 offset。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BannerEventConsumer {

    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final BannerCacheRefreshService refreshService;
    private final BannerRedisRepository redisRepository;

    @KafkaListener(
            topics = "${banner.kafka.event-topic}",
            groupId = "${spring.kafka.consumer.group-id}")
    public void consume(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        BannerDeliveryEvent event = deserialize(record.value());
        validate(event);
        String expectedKey = String.valueOf(event.getBannerId());
        if (!expectedKey.equals(record.key())) {
            log.warn("Banner event key mismatch: expected={}, actual={}, eventId={}",
                    expectedKey, record.key(), event.getEventId());
        }

        // 任一步骤抛出异常时不会执行 acknowledge，DefaultErrorHandler 将 seek 后重投。
        refreshService.refreshFromMysql(event);
        redisRepository.recordConsumeSuccess(event.getBannerId(), event.getEventTime());
        acknowledgment.acknowledge();
    }

    private BannerDeliveryEvent deserialize(String payload) {
        try {
            return objectMapper.readValue(payload, BannerDeliveryEvent.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid banner event JSON", exception);
        }
    }

    private void validate(BannerDeliveryEvent event) {
        Set<ConstraintViolation<BannerDeliveryEvent>> violations = validator.validate(event);
        if (!violations.isEmpty()) {
            String message = violations.stream()
                    .map(violation -> violation.getPropertyPath() + ": "
                            + violation.getMessage())
                    .sorted()
                    .collect(Collectors.joining(", "));
            throw new IllegalArgumentException("Invalid banner event: " + message);
        }
        BannerDateTimeUtils.parseDateTime(event.getEventTime(), "eventTime");
    }
}
