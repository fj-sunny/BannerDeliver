package com.bannerdeliver.service;

import com.bannerdeliver.domain.po.BannerCrowd;

import java.util.List;

/**
 * Banner 人群包业务服务。
 */
public interface BannerCrowdService {

    List<BannerCrowd> findByBannerId(Long bannerId);

    /**
     * 在一个事务中替换 Banner 的全部人群包分页，并更新 banner_info.update_time。
     * 事务提交后发送 AUDIENCE_UPDATE 事件。
     */
    void replaceCrowd(Long bannerId, List<BannerCrowd> crowds);
}
