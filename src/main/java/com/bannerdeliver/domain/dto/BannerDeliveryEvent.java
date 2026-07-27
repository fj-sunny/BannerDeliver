package com.bannerdeliver.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Kafka 缓存刷新事件消息体。
 * Consumer 用 bannerId 定位记录、用 old* 清理旧 Redis、用 eventId 作 audienceBatch；
 * 业务字段以 MySQL 重读为准。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BannerDeliveryEvent {

    /** 事件唯一 ID，同时作为人群包 audienceBatch。 */
    @NotBlank
    @Pattern(regexp = "[A-Za-z0-9._-]+")
    private String eventId;

    /** 事件类型，决定刷新语义（配置变更/人群变更/下线等）。 */
    @NotNull
    private BannerEventType eventType;

    /** 目标 Banner 主键，也是 Kafka message key。 */
    @NotNull
    private Long bannerId;

    /** 变更后的商品 ID（辅助信息，刷新以 MySQL 为准）。 */
    private Long productId;
    /** 变更前的商品 ID，用于 HDEL 旧日期 Hash。 */
    private Long oldProductId;
    /** 变更前的投放开始时间，用于 HDEL 旧日期 Hash。 */
    private String oldBeginTime;
    /** 变更前的投放结束时间，用于 HDEL 旧日期 Hash。 */
    private String oldEndTime;

    /** 本次变更涉及的字段名列表，便于审计。 */
    @Builder.Default
    private List<String> changedFields = new ArrayList<>();

    /** 事件发生时间，格式 yyyy-MM-dd HH:mm:ss，用于对账窗口划分。 */
    @NotBlank
    private String eventTime;
}
