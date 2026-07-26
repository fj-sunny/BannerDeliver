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

/** Banner 人群包的读取与整体替换服务；唯一实现直接收敛在此类。 */
@Service
@RequiredArgsConstructor
public class BannerCrowdService {
    private final BannerCrowdMapper bannerCrowdMapper;
    private final BannerInfoMapper bannerInfoMapper;
    private final BannerEventProducer eventProducer;
    private final Clock bannerClock;

    /** 按 bannerId 查询全部人群包分页，按 pageNum 升序。 */
    public List<BannerCrowd> findByBannerId(Long bannerId) {
        return List.copyOf(bannerCrowdMapper.selectList(Wrappers.<BannerCrowd>lambdaQuery()
                .eq(BannerCrowd::getBannerId, bannerId).orderByAsc(BannerCrowd::getPageNum)));
    }

    /** 整体替换 Banner 人群包分页，更新 Banner 版本并发送 Kafka 事件。 */
    @Transactional
    public void replaceCrowd(Long bannerId, List<BannerCrowd> crowds) {
        BannerInfo banner = requireBanner(bannerId);
        List<BannerCrowd> safeCrowds = crowds == null ? List.of() : new ArrayList<>(crowds);
        validatePages(safeCrowds);
        String eventTime = BannerDateTimeUtils.DATE_TIME_FORMATTER.format(LocalDateTime.now(bannerClock));
        bannerCrowdMapper.delete(Wrappers.<BannerCrowd>lambdaQuery().eq(BannerCrowd::getBannerId, bannerId));
        for (BannerCrowd crowd : safeCrowds) {
            BannerCrowd insert = BannerCrowd.builder().bannerId(bannerId).pageNum(crowd.getPageNum())
                    .userList(crowd.getUserList()).createTime(eventTime).updateTime(eventTime).build();
            if (bannerCrowdMapper.insert(insert) != 1) {
                throw new BusinessException("Banner 人群包分页写入失败: bannerId=" + bannerId
                        + ", pageNum=" + insert.getPageNum());
            }
        }
        if (bannerInfoMapper.updateById(BannerInfo.builder().bannerId(bannerId).updateTime(eventTime).build()) != 1) {
            throw new BusinessException("Banner 人群包更新时间失败: " + bannerId);
        }
        eventProducer.sendAfterCommit(BannerDeliveryEvent.builder()
                .eventId("evt_" + UUID.randomUUID()).eventType(BannerEventType.AUDIENCE_UPDATE)
                .bannerId(bannerId).productId(banner.getProductId()).oldProductId(banner.getProductId())
                .changedFields(List.of("user_list")).eventTime(eventTime).build());
    }

    /** 校验 bannerId 非空且 Banner 存在于 MySQL。 */
    private BannerInfo requireBanner(Long bannerId) {
        if (bannerId == null) throw new ParamException("bannerId 不能为空");
        BannerInfo banner = bannerInfoMapper.selectById(bannerId);
        if (banner == null) throw new BusinessException("Banner 不存在: " + bannerId);
        return banner;
    }

    /** 校验人群包分页号非负、不重复，且 userList 不为空。 */
    private void validatePages(List<BannerCrowd> crowds) {
        Set<Integer> pageNumbers = new HashSet<>();
        for (BannerCrowd crowd : crowds) {
            if (crowd == null || crowd.getPageNum() == null || crowd.getPageNum() < 0)
                throw new ParamException("pageNum 必须为非负整数");
            if (crowd.getUserList() == null) throw new ParamException("userList 不能为空");
            if (!pageNumbers.add(crowd.getPageNum()))
                throw new ParamException("pageNum 不能重复: " + crowd.getPageNum());
        }
    }
}
