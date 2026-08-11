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

    /** 事件唯一 ID；人群变更时同时作为新的 audienceBatch。 */
    private String eventId;

    /** 目标 Banner 主键，也是 Kafka message key。 */
    private Long bannerId;

    /** 需要更新的 Redis 数据部分。 */
    private BannerEventType eventType;

    /** 事件发生时间，Unix 毫秒，用于对账窗口划分。 */
    private Long eventTime;
}
