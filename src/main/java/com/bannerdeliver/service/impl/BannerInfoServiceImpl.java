package com.bannerdeliver.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bannerdeliver.domain.dto.BannerDeliveryEvent;
import com.bannerdeliver.domain.dto.BannerEventType;
import com.bannerdeliver.domain.po.BannerInfo;
import com.bannerdeliver.exception.BusinessException;
import com.bannerdeliver.exception.ParamException;
import com.bannerdeliver.kafka.producer.BannerEventProducer;
import com.bannerdeliver.mapper.BannerInfoMapper;
import com.bannerdeliver.service.BannerInfoService;
import com.bannerdeliver.utils.BannerDateTimeUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Banner 基础配置业务实现。
 */
@Service
@RequiredArgsConstructor
public class BannerInfoServiceImpl implements BannerInfoService {

    private final BannerInfoMapper bannerInfoMapper;
    private final BannerEventProducer eventProducer;
    private final Clock bannerClock;

    @Override
    public BannerInfo findById(Long bannerId) {
        return bannerInfoMapper.selectById(bannerId);
    }

    @Override
    public List<BannerInfo> findUpdatedBetween(
            LocalDateTime windowStart, LocalDateTime windowEnd) {
        return List.copyOf(bannerInfoMapper.selectList(
                Wrappers.<BannerInfo>lambdaQuery()
                        .ge(BannerInfo::getUpdateTime,
                                BannerDateTimeUtils.DATE_TIME_FORMATTER.format(windowStart))
                        .lt(BannerInfo::getUpdateTime,
                                BannerDateTimeUtils.DATE_TIME_FORMATTER.format(windowEnd))
                        .orderByAsc(BannerInfo::getBannerId)));
    }

    @Override
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
        Long productId = banner.getProductId() == null
                ? current.getProductId() : banner.getProductId();
        eventProducer.sendAfterCommit(BannerDeliveryEvent.builder()
                .eventId(eventId())
                .eventType(BannerEventType.BANNER_UPDATE)
                .bannerId(banner.getBannerId())
                .productId(productId)
                .oldProductId(current.getProductId())
                .changedFields(changedFields == null ? List.of() : List.copyOf(changedFields))
                .eventTime(eventTime)
                .build());
    }

    @Override
    @Transactional
    public void offlineBanner(Long bannerId) {
        BannerInfo current = requireBanner(bannerId);
        String eventTime = now();
        BannerInfo update = BannerInfo.builder()
                .bannerId(bannerId)
                .status(0)
                .updateTime(eventTime)
                .build();
        if (bannerInfoMapper.updateById(update) != 1) {
            throw new BusinessException("Banner 下线失败: " + bannerId);
        }
        eventProducer.sendAfterCommit(BannerDeliveryEvent.builder()
                .eventId(eventId())
                .eventType(BannerEventType.BANNER_OFFLINE)
                .bannerId(bannerId)
                .productId(current.getProductId())
                .oldProductId(current.getProductId())
                .changedFields(List.of("status"))
                .eventTime(eventTime)
                .build());
    }

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

    private String now() {
        return BannerDateTimeUtils.DATE_TIME_FORMATTER.format(
                LocalDateTime.now(bannerClock));
    }

    private String eventId() {
        return "evt_" + UUID.randomUUID();
    }
}
