package com.bannerdeliver.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bannerdeliver.domain.po.BannerInfo;
import com.bannerdeliver.mapper.BannerInfoMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Banner 配置 MySQL 只读服务。
 *
 * <p>Banner 配置的新增/修改/下线由运营端写 MySQL 并投递 Kafka；
 * 本服务只提供查询，供缓存刷新与定时对账使用。</p>
 */
@Service
@RequiredArgsConstructor
public class BannerInfoService {

    private final BannerInfoMapper bannerInfoMapper;

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
}
