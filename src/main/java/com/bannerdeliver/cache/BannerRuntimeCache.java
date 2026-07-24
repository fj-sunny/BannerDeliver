package com.bannerdeliver.cache;

import com.bannerdeliver.domain.dto.BannerRuntimeDTO;

import java.time.LocalDate;
import java.util.List;

/**
 * Banner 运行时缓存的业务访问协议。
 *
 * <p>业务查询只依赖该接口，不直接依赖 Redis API。当前实现是
 * {@code BannerRedisRepository}，以后可以替换为其他缓存实现。</p>
 */
public interface BannerRuntimeCache {

    /** 读取指定商品、日期下的全部 Banner Runtime。 */
    List<BannerRuntimeDTO> findBanners(Long productId, LocalDate date);

    /** 根据 Runtime 中的分桶信息判断用户是否命中人群包。 */
    boolean isAudienceMember(BannerRuntimeDTO runtime, String userId);
}
