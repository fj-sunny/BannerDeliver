package com.bannerdeliver.kafka.consumer;

import com.bannerdeliver.domain.dto.BannerDeliveryEvent;
import com.bannerdeliver.service.BannerCacheService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Kafka 消费：MySQL 重读 → 刷新 Redis → 记录消费窗口 → 手动 ack。
 *
 * <p>失败不 ack，由 ErrorHandler 固定间隔重投；eventId 作 audienceBatch 保证幂等。</p>
 */
@Component
@RequiredArgsConstructor
public class BannerEventConsumer {

    private final ObjectMapper objectMapper;
    private final BannerCacheService cacheService;

    /** 刷新 Redis 和本地缓存，全部成功后才提交 offset。 */
    @KafkaListener(
            topics = "${banner.kafka.event-topic}",
            groupId = "${spring.kafka.consumer.group-id}")
    public void consume(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        BannerDeliveryEvent event;
        try {
            event = objectMapper.readValue(record.value(), BannerDeliveryEvent.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to parse banner event", exception);
        }
        if (event.getBannerId() == null
                || event.getEventId() == null
                || !event.getEventId().matches("[A-Za-z0-9._-]+")
                || event.getEventType() == null
                || event.getEventTime() == null) {
            throw new IllegalArgumentException("Invalid banner event");
        }
        cacheService.refreshBannerCache(
                event.getBannerId(), event.getEventId(), event.getEventType());
        acknowledgment.acknowledge();
    }
}
