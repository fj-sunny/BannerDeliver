package com.bannerdeliver.kafka.producer;

import com.bannerdeliver.config.BannerProperties;
import com.bannerdeliver.domain.dto.BannerDeliveryEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
    private final BannerProperties properties;

    /** 在当前数据库事务提交后再发送 Kafka，避免消费端读到未提交数据。 */
    public void sendAfterCommit(BannerDeliveryEvent event) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            send(event);
                        }
                    });
        } else {
            send(event);
        }
    }

    private void send(BannerDeliveryEvent event) {
        try {
            kafkaTemplate.send(
                            properties.getKafka().getEventTopic(),
                            String.valueOf(event.getBannerId()),
                            objectMapper.writeValueAsString(event))
                    .whenComplete((result, exception) -> {
                        if (exception != null) {
                            log.error("Failed to send banner event: bannerId={}, eventId={}",
                                    event.getBannerId(), event.getEventId(), exception);
                        }
                    });
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to serialize banner event", exception);
        }
    }
}
