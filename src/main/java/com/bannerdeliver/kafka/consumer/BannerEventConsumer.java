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
 * Kafka 消费：MySQL 重读 → 刷新 Redis（人群变更时新增 batch 并切换 Runtime 指针）
 * → 删除 LocalCache → 手动 ack。
 *
 * <p>失败不 ack，由 ErrorHandler 固定间隔重投。
 * {@code eventId} 作为新 {@code audienceBatch}；旧 Redis 人群 Key 不删，靠 TTL 过期。</p>
 */
@Component
@RequiredArgsConstructor
public class BannerEventConsumer {

    private final ObjectMapper objectMapper;
    private final BannerCacheService cacheService;

    /** 刷新 Redis 并删除本地缓存，全部成功后才提交 offset。 */
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
                || event.getEventType() == null) {
            throw new IllegalArgumentException("Invalid banner event");
        }
        // eventTime 可选，仅便于排查；刷新与对账版本以 MySQL update_time 为准。
        cacheService.refreshBannerCache(
                event.getBannerId(), event.getEventId(), event.getEventType());
        acknowledgment.acknowledge();
    }
}
