package com.bannerdeliver.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bannerdeliver.domain.dto.BannerDeliveryEvent;
import com.bannerdeliver.domain.dto.BannerEventType;
import com.bannerdeliver.domain.po.BannerInfo;
import com.bannerdeliver.exception.BusinessException;
import com.bannerdeliver.exception.ParamException;
import com.bannerdeliver.kafka.producer.BannerEventProducer;
import com.bannerdeliver.mapper.BannerInfoMapper;
import com.bannerdeliver.utils.BannerDateTimeUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Banner 配置写库服务。
 * 负责 MySQL 增删改，并在事务提交后发送 Kafka 刷新事件；old 投放范围在 UPDATE 前快照进 Event。
 */
@Service
@RequiredArgsConstructor
public class BannerInfoService {

    private final BannerInfoMapper bannerInfoMapper;
    private final BannerEventProducer eventProducer;
    private final Clock bannerClock;

    /** 按主键查询 Banner 配置，不存在时返回 null。 */
    public BannerInfo findById(Long bannerId) {
        return bannerInfoMapper.selectById(bannerId);
    }

    /** 查询对账窗口 [windowStart, windowEnd) 内 update_time 有变更的 Banner 列表。 */
    public List<BannerInfo> findUpdatedBetween(LocalDateTime windowStart, LocalDateTime windowEnd) {
        return List.copyOf(bannerInfoMapper.selectList(Wrappers.<BannerInfo>lambdaQuery()
                .ge(BannerInfo::getUpdateTime, BannerDateTimeUtils.DATE_TIME_FORMATTER.format(windowStart))
                .lt(BannerInfo::getUpdateTime, BannerDateTimeUtils.DATE_TIME_FORMATTER.format(windowEnd))
                .orderByAsc(BannerInfo::getBannerId)));
    }

    /**
     * 更新 Banner 配置并发送 BANNER_UPDATE 事件。
     * 先 SELECT 旧值填 old*，再 UPDATE，最后 sendAfterCommit。
     */
    @Transactional
    public void updateBanner(BannerInfo banner, List<String> changedFields) {
        if (banner == null || banner.getBannerId() == null) {
            throw new ParamException("bannerId 不能为空");
        }
        BannerInfo current = requireBanner(banner.getBannerId());
        String eventTime = now();
        banner.setUpdateTime(eventTime);
        if (bannerInfoMapper.updateById(banner) != 1) {
            throw new BusinessException("Banner 更新失败: " + banner.getBannerId());
        }
        Long productId = banner.getProductId() == null ? current.getProductId() : banner.getProductId();
        eventProducer.sendAfterCommit(BannerDeliveryEvent.builder()
                .eventId(eventId())
                .eventType(BannerEventType.BANNER_UPDATE)
                .bannerId(banner.getBannerId())
                .productId(productId)
                .oldProductId(current.getProductId())
                .oldBeginTime(current.getBeginTime())
                .oldEndTime(current.getEndTime())
                .changedFields(changedFields == null ? List.of() : List.copyOf(changedFields))
                .eventTime(eventTime)
                .build());
    }

    /**
     * 将 Banner 下线（status=0）并发送 BANNER_OFFLINE 事件。
     * old 投放范围来自下线前的 current 快照，供 Consumer 清理 Redis。
     */
    @Transactional
    public void offlineBanner(Long bannerId) {
        BannerInfo current = requireBanner(bannerId);
        String eventTime = now();
        BannerInfo update = BannerInfo.builder()
                .bannerId(bannerId).status(0).updateTime(eventTime).build();
        if (bannerInfoMapper.updateById(update) != 1) {
            throw new BusinessException("Banner 下线失败: " + bannerId);
        }
        eventProducer.sendAfterCommit(BannerDeliveryEvent.builder()
                .eventId(eventId())
                .eventType(BannerEventType.BANNER_OFFLINE)
                .bannerId(bannerId)
                .productId(current.getProductId())
                .oldProductId(current.getProductId())
                .oldBeginTime(current.getBeginTime())
                .oldEndTime(current.getEndTime())
                .changedFields(List.of("status"))
                .eventTime(eventTime)
                .build());
    }

    /** 校验 bannerId 非空且记录存在，否则抛 ParamException / BusinessException。 */
    private BannerInfo requireBanner(Long bannerId) {
        if (bannerId == null) {
            throw new ParamException("bannerId 不能为空");
        }
        BannerInfo banner = bannerInfoMapper.selectById(bannerId);
        if (banner == null) {
            throw new BusinessException("Banner 不存在: " + bannerId);
        }
        return banner;
    }

    /** 返回当前业务时钟格式化的 yyyy-MM-dd HH:mm:ss 字符串。 */
    private String now() {
        return BannerDateTimeUtils.DATE_TIME_FORMATTER.format(LocalDateTime.now(bannerClock));
    }

    /** 生成唯一 Kafka eventId，同时作为人群包 audienceBatch。 */
    private String eventId() {
        return "evt_" + UUID.randomUUID();
    }
}
