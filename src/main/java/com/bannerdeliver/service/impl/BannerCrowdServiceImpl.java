package com.bannerdeliver.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bannerdeliver.domain.dto.BannerDeliveryEvent;
import com.bannerdeliver.domain.dto.BannerEventType;
import com.bannerdeliver.domain.po.BannerCrowd;
import com.bannerdeliver.domain.po.BannerInfo;
import com.bannerdeliver.exception.BusinessException;
import com.bannerdeliver.exception.ParamException;
import com.bannerdeliver.kafka.producer.BannerEventProducer;
import com.bannerdeliver.mapper.BannerCrowdMapper;
import com.bannerdeliver.mapper.BannerInfoMapper;
import com.bannerdeliver.service.BannerCrowdService;
import com.bannerdeliver.utils.BannerDateTimeUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Banner 人群包业务实现。
 */
@Service
@RequiredArgsConstructor
public class BannerCrowdServiceImpl implements BannerCrowdService {

    private final BannerCrowdMapper bannerCrowdMapper;
    private final BannerInfoMapper bannerInfoMapper;
    private final BannerEventProducer eventProducer;
    private final Clock bannerClock;

    @Override
    public List<BannerCrowd> findByBannerId(Long bannerId) {
        return List.copyOf(bannerCrowdMapper.selectList(
                Wrappers.<BannerCrowd>lambdaQuery()
                        .eq(BannerCrowd::getBannerId, bannerId)
                        .orderByAsc(BannerCrowd::getPageNum)));
    }

    @Override
    @Transactional
    public void replaceCrowd(Long bannerId, List<BannerCrowd> crowds) {
        BannerInfo banner = requireBanner(bannerId);
        List<BannerCrowd> safeCrowds =
                crowds == null ? List.of() : new ArrayList<>(crowds);
        validatePages(safeCrowds);
        String eventTime = BannerDateTimeUtils.DATE_TIME_FORMATTER.format(
                LocalDateTime.now(bannerClock));

        bannerCrowdMapper.delete(Wrappers.<BannerCrowd>lambdaQuery()
                .eq(BannerCrowd::getBannerId, bannerId));
        for (BannerCrowd crowd : safeCrowds) {
            BannerCrowd insert = BannerCrowd.builder()
                    .bannerId(bannerId)
                    .pageNum(crowd.getPageNum())
                    .userList(crowd.getUserList())
                    .createTime(eventTime)
                    .updateTime(eventTime)
                    .build();
            if (bannerCrowdMapper.insert(insert) != 1) {
                throw new BusinessException(
                        "Banner 人群包分页写入失败: bannerId=" + bannerId
                                + ", pageNum=" + insert.getPageNum());
            }
        }
        BannerInfo update = BannerInfo.builder()
                .bannerId(bannerId)
                .updateTime(eventTime)
                .build();
        if (bannerInfoMapper.updateById(update) != 1) {
            throw new BusinessException("Banner 人群包更新时间失败: " + bannerId);
        }

        eventProducer.sendAfterCommit(BannerDeliveryEvent.builder()
                .eventId("evt_" + UUID.randomUUID())
                .eventType(BannerEventType.AUDIENCE_UPDATE)
                .bannerId(bannerId)
                .productId(banner.getProductId())
                .oldProductId(banner.getProductId())
                .changedFields(List.of("user_list"))
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

    private void validatePages(List<BannerCrowd> crowds) {
        Set<Integer> pageNumbers = new HashSet<>();
        for (BannerCrowd crowd : crowds) {
            if (crowd == null || crowd.getPageNum() == null || crowd.getPageNum() < 0) {
                throw new ParamException("pageNum 必须为非负整数");
            }
            if (crowd.getUserList() == null) {
                throw new ParamException("userList 不能为空");
            }
            if (!pageNumbers.add(crowd.getPageNum())) {
                throw new ParamException("pageNum 不能重复: " + crowd.getPageNum());
            }
        }
    }
}
