package com.bannerdeliver.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bannerdeliver.domain.dto.BannerDeliveryEvent;
import com.bannerdeliver.domain.dto.BannerEventType;
import com.bannerdeliver.domain.po.BannerInfo;
import com.bannerdeliver.exception.BusinessException;
import com.bannerdeliver.exception.ParamException;
import com.bannerdeliver.kafka.producer.BannerEventProducer;
import com.bannerdeliver.mapper.BannerInfoMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** Banner 配置写库服务，事务提交后发送只包含定位信息的 Kafka 刷新通知。 */
@Service
@RequiredArgsConstructor
public class BannerInfoService {

    private final BannerInfoMapper bannerInfoMapper;
    private final BannerEventProducer eventProducer;

    /** 按主键查询 Banner 配置，不存在时返回 null。 */
    public BannerInfo findById(Long bannerId) {
        return bannerInfoMapper.selectById(bannerId);
    }

    /** 查询对账窗口 [windowStart, windowEnd) 内 update_time 有变更的 Banner 列表。 */
    public List<BannerInfo> findUpdatedBetween(Long windowStart, Long windowEnd) {
        return List.copyOf(bannerInfoMapper.selectList(Wrappers.<BannerInfo>lambdaQuery()
                .ge(BannerInfo::getUpdateTime, windowStart)
                .lt(BannerInfo::getUpdateTime, windowEnd)
                .orderByAsc(BannerInfo::getBannerId)));
    }

    /** 更新 Banner 配置并发送缓存刷新通知。 */
    @Transactional
    public void updateBanner(BannerInfo banner) {
        if (banner == null || banner.getBannerId() == null) {
            throw new ParamException("bannerId 不能为空");
        }
        Long eventTime = System.currentTimeMillis();
        banner.setUpdateTime(eventTime);
        if (bannerInfoMapper.updateById(banner) != 1) {
            throw new BusinessException("Banner 更新失败: " + banner.getBannerId());
        }
        eventProducer.sendAfterCommit(BannerDeliveryEvent.builder()
                .eventId(eventId())
                .bannerId(banner.getBannerId())
                .eventType(BannerEventType.BANNER_UPDATE)
                .eventTime(eventTime)
                .build());
    }

    /** 将 Banner 下线并发送缓存刷新通知。 */
    @Transactional
    public void offlineBanner(Long bannerId) {
        if (bannerId == null) {
            throw new ParamException("bannerId 不能为空");
        }
        Long eventTime = System.currentTimeMillis();
        BannerInfo update = BannerInfo.builder()
                .bannerId(bannerId).status(0).updateTime(eventTime).build();
        if (bannerInfoMapper.updateById(update) != 1) {
            throw new BusinessException("Banner 下线失败: " + bannerId);
        }
        eventProducer.sendAfterCommit(BannerDeliveryEvent.builder()
                .eventId(eventId())
                .bannerId(bannerId)
                .eventType(BannerEventType.BANNER_UPDATE)
                .eventTime(eventTime)
                .build());
    }

    /** 生成唯一 Kafka eventId，同时作为人群包 audienceBatch。 */
    private String eventId() {
        return "evt_" + UUID.randomUUID();
    }
}
