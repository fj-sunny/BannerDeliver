package com.bannerdeliver.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Kafka 缓存刷新通知，业务数据统一由消费者从 MySQL 读取。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BannerDeliveryEvent {

    /** 事件唯一 ID；人群变更时作为 Redis 新 audienceBatch（不落 MySQL）。 */
    private String eventId;

    /** 目标 Banner 主键，也是 Kafka message key。 */
    private Long bannerId;

    /** 需要更新的 Redis 数据部分。 */
    private BannerEventType eventType;

    /**
     * 事件发生时间，Unix 毫秒；可选，供排查/审计。
     * 对账与 Redis {@code updateTime} 均以 MySQL {@code update_time} 为准，不使用本字段。
     */
    private Long eventTime;
}
