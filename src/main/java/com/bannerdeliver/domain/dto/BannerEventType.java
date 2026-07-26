package com.bannerdeliver.domain.dto;

/** Kafka 缓存刷新事件类型。 */
public enum BannerEventType {
    /** Banner 配置字段变更。 */
    BANNER_UPDATE,
    /** 人群包 user_list 变更。 */
    AUDIENCE_UPDATE,
    /** 全量更新（预留）。 */
    FULL_UPDATE,
    /** Banner 下线。 */
    BANNER_OFFLINE,
    /** Banner 删除（预留）。 */
    BANNER_DELETE
}
