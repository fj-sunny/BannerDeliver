package com.bannerdeliver.domain.dto;

/** Redis 缓存需要更新的业务部分。 */
public enum BannerEventType {
    /** Banner 时间、状态、URL 等基础信息变化。 */
    BANNER_UPDATE,
    /** Banner 人群包变化。 */
    AUDIENCE_UPDATE,
    /** Banner 基础信息和人群包同时变化。 */
    FULL_UPDATE
}
