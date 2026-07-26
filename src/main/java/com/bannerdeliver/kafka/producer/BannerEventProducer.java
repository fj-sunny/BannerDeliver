package com.bannerdeliver.kafka.producer;

import com.bannerdeliver.config.BannerProperties;
import com.bannerdeliver.domain.dto.BannerDeliveryEvent;
import com.bannerdeliver.utils.BannerDateTimeUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Banner 缓存刷新事件生产者。
 *
 * <p>MySQL 事务提交后再发送 Kafka，message key 固定为 {@code bannerId} 以保证同分区顺序。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BannerEventProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final BannerProperties properties;

    /** 同步发送 Banner 刷新事件到 Kafka，key 为 bannerId。 */
    public CompletableFuture<SendResult<String, String>> send(BannerDeliveryEvent event) {
        validateEvent(event);
        BannerDateTimeUtils.parseDateTime(event.getEventTime(), "eventTime");
        try {
            return kafkaTemplate.send(
                    properties.getKafka().getEventTopic(),
                    String.valueOf(event.getBannerId()),
                    objectMapper.writeValueAsString(event));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to serialize banner event", exception);
        }
    }

    /** 在当前数据库事务提交后再发送 Kafka，避免消费端读到未提交数据。 */
    public void sendAfterCommit(BannerDeliveryEvent event) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        /** 事务提交回调：异步发送 Kafka 事件。 */
                        @Override
                        public void afterCommit() {
                            sendWithFailureLog(event);
                        }
                    });
        } else {
            sendWithFailureLog(event);
        }
    }

    /** 校验事件必填字段与 eventTime 格式。 */
    private void validateEvent(BannerDeliveryEvent event) {
        Set<ConstraintViolation<BannerDeliveryEvent>> violations = validator.validate(event);
        if (!violations.isEmpty()) {
            throw new IllegalArgumentException("Invalid banner event: " + violations);
        }
    }

    /** 发送 Kafka 事件，失败时记录 error 日志但不抛异常。 */
    private void sendWithFailureLog(BannerDeliveryEvent event) {
        send(event).whenComplete((result, exception) -> {
            if (exception != null) {
                log.error(
                        "Failed to send banner event: bannerId={}, eventId={}",
                        event.getBannerId(),
                        event.getEventId(),
                        exception);
            }
        });
    }
}
