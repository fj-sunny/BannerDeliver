package com.bannerdeliver.service;

import com.bannerdeliver.domain.po.BannerInfo;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Banner 基础配置业务服务。
 *
 * <p>提供明确的 MySQL 查询、配置更新和下线能力，不向上层暴露 MyBatis-Plus 通用接口。</p>
 */
public interface BannerInfoService {

    BannerInfo findById(Long bannerId);

    List<BannerInfo> findUpdatedBetween(LocalDateTime windowStart, LocalDateTime windowEnd);

    /**
     * 更新 Banner 配置；事务提交后发送 BANNER_UPDATE 事件。
     */
    void updateBanner(BannerInfo banner, List<String> changedFields);

    /**
     * 将 Banner 状态更新为下线；事务提交后发送 BANNER_OFFLINE 事件。
     */
    void offlineBanner(Long bannerId);
}
