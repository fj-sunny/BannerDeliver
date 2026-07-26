package com.bannerdeliver.kafka.consumer;

import com.bannerdeliver.domain.dto.BannerDeliveryEvent;
import com.bannerdeliver.service.BannerCacheService;
import com.bannerdeliver.utils.BannerDateTimeUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Kafka 消费：MySQL 重读 → 刷新 Redis → 记录消费窗口 → 手动 ack。
 *
 * <p>失败不 ack，由 ErrorHandler 固定间隔重投；eventId 作 audienceBatch 保证幂等。</p>
 */
@Component
@RequiredArgsConstructor
public class BannerEventConsumer {

    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final BannerCacheService cacheService;

    /**
     * 消费 Kafka Banner 刷新事件。
     * 刷新 Redis 并记录消费窗口后手动 ack，任一步失败则不提交 offset。
     */
    @KafkaListener(
            topics = "${banner.kafka.event-topic}",
            groupId = "${spring.kafka.consumer.group-id}")
    public void consume(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        BannerDeliveryEvent event = parseEvent(record.value());
        validateEvent(event);
        BannerDateTimeUtils.parseDateTime(event.getEventTime(), "eventTime");

        cacheService.refreshFromMysql(event);
        cacheService.recordConsumeSuccess(event.getBannerId(), event.getEventTime());
        acknowledgment.acknowledge();
    }

    /** 将 Kafka 消息 JSON 反序列化为 BannerDeliveryEvent。 */
    private BannerDeliveryEvent parseEvent(String payload) {
        try {
            return objectMapper.readValue(payload, BannerDeliveryEvent.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to parse banner event", exception);
        }
    }

    /** 使用 Bean Validation 校验事件必填字段与格式。 */
    private void validateEvent(BannerDeliveryEvent event) {
        Set<ConstraintViolation<BannerDeliveryEvent>> violations = validator.validate(event);
        if (!violations.isEmpty()) {
            throw new IllegalArgumentException("Invalid banner event: " + violations);
        }
    }
}
