package com.bannerdeliver.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bannerdeliver.domain.po.BannerCrowd;
import com.bannerdeliver.mapper.BannerCrowdMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Banner 人群包 MySQL 只读服务。
 *
 * <p>人群包增删改由运营端负责；本服务仅在 Kafka 消费 / 对账时读取当前最新源数据，
 * 再由 {@link BannerCacheService} 新增 Redis audienceBatch 并切换 {@code BannerRuntimeDTO}。</p>
 */
@Service
@RequiredArgsConstructor
public class BannerCrowdService {

    private final BannerCrowdMapper bannerCrowdMapper;

    /** 按 bannerId 查询 MySQL 当前最新人群包分页，按 pageNum 升序。 */
    public List<BannerCrowd> findByBannerId(Long bannerId) {
        return List.copyOf(bannerCrowdMapper.selectList(Wrappers.<BannerCrowd>lambdaQuery()
                .eq(BannerCrowd::getBannerId, bannerId).orderByAsc(BannerCrowd::getPageNum)));
    }
}
