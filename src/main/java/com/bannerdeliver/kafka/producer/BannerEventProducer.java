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
import java.util.stream.Collectors;

/**
 * Kafka 生产者。
 *
 * <p>统一负责事件校验、JSON 序列化以及 message key。message key 固定使用 bannerId，
 * 从而保证同一 Banner 的事件进入同一个 Kafka 分区。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BannerEventProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final BannerProperties properties;

    /**
     * message key 固定为 bannerId，保证同一 Banner 进入同一分区并保持顺序。
     */
    public CompletableFuture<SendResult<String, String>> send(BannerDeliveryEvent event) {
        validate(event);
        try {
            String payload = objectMapper.writeValueAsString(event);
            return kafkaTemplate.send(
                    properties.getKafka().getEventTopic(),
                    String.valueOf(event.getBannerId()),
                    payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to serialize banner event", exception);
        }
    }

    /**
     * MySQL 事务提交后发送 Kafka，避免数据库回滚但消息已经对外可见。
     *
     * <p>如果当前没有事务则立即发送。异步发送失败会记录错误日志，五分钟定时对账负责兜底。</p>
     */
    public void sendAfterCommit(BannerDeliveryEvent event) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            sendWithFailureLog(event);
                        }
                    });
            return;
        }
        sendWithFailureLog(event);
    }

    private void sendWithFailureLog(BannerDeliveryEvent event) {
        send(event).whenComplete((result, exception) -> {
            if (exception != null) {
                log.error("Failed to send banner event after DB commit: "
                                + "bannerId={}, eventId={}",
                        event.getBannerId(), event.getEventId(), exception);
            }
        });
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
