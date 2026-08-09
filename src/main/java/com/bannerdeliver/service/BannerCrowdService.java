package com.bannerdeliver.service;

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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Banner 人群包写库服务。
 * 全量替换指定 bannerId 的分页人群数据，并发送 AUDIENCE_UPDATE 事件触发缓存刷新。
 */
@Service
@RequiredArgsConstructor
public class BannerCrowdService {

    private final BannerCrowdMapper bannerCrowdMapper;
    private final BannerInfoMapper bannerInfoMapper;
    private final BannerEventProducer eventProducer;

    /** 按 bannerId 查询全部人群包分页，按 pageNum 升序。 */
    public List<BannerCrowd> findByBannerId(Long bannerId) {
        return List.copyOf(bannerCrowdMapper.selectList(Wrappers.<BannerCrowd>lambdaQuery()
                .eq(BannerCrowd::getBannerId, bannerId).orderByAsc(BannerCrowd::getPageNum)));
    }

    /** 全量替换人群包，更新 Banner 时间后发送缓存刷新通知。 */
    @Transactional
    public void replaceCrowd(Long bannerId, List<BannerCrowd> crowds) {
        if (bannerId == null) {
            throw new ParamException("bannerId 不能为空");
        }
        if (bannerInfoMapper.selectById(bannerId) == null) {
            throw new BusinessException("Banner 不存在: " + bannerId);
        }
        List<BannerCrowd> safeCrowds = crowds == null ? List.of() : new ArrayList<>(crowds);
        Set<Integer> pageNumbers = new HashSet<>();
        for (BannerCrowd crowd : safeCrowds) {
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
        Long eventTime = System.currentTimeMillis();
        bannerCrowdMapper.delete(Wrappers.<BannerCrowd>lambdaQuery().eq(BannerCrowd::getBannerId, bannerId));
        for (BannerCrowd crowd : safeCrowds) {
            BannerCrowd insert = BannerCrowd.builder()
                    .bannerId(bannerId)
                    .pageNum(crowd.getPageNum())
                    .userList(crowd.getUserList())
                    .createTime(eventTime)
                    .updateTime(eventTime)
                    .build();
            if (bannerCrowdMapper.insert(insert) != 1) {
                throw new BusinessException("Banner 人群包分页写入失败: bannerId=" + bannerId
                        + ", pageNum=" + insert.getPageNum());
            }
        }
        if (bannerInfoMapper.updateById(BannerInfo.builder()
                .bannerId(bannerId).updateTime(eventTime).build()) != 1) {
            throw new BusinessException("Banner 人群包更新时间失败: " + bannerId);
        }
        eventProducer.sendAfterCommit(BannerDeliveryEvent.builder()
                .eventId("evt_" + UUID.randomUUID())
                .bannerId(bannerId)
                .eventType(BannerEventType.AUDIENCE_UPDATE)
                .eventTime(eventTime)
                .build());
    }
}
