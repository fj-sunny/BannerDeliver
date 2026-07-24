package com.bannerdeliver.service;

import com.bannerdeliver.domain.po.BannerCrowd;
import com.bannerdeliver.domain.po.BannerInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 从 MySQL 读取 Banner 及人群包的一致业务快照。
 *
 * <p>事务只覆盖数据库读取，避免在较慢的 Redis 写入期间长期占用数据库事务。</p>
 */
@Service
@RequiredArgsConstructor
public class BannerSnapshotService {

    private final BannerInfoService bannerInfoService;
    private final BannerCrowdService bannerCrowdService;

    @Transactional(readOnly = true)
    public BannerSnapshot load(Long bannerId) {
        BannerInfo banner = bannerInfoService.findById(bannerId);
        if (banner == null) {
            return new BannerSnapshot(null, List.of());
        }
        List<BannerCrowd> crowds = bannerCrowdService.findByBannerId(bannerId);
        return new BannerSnapshot(banner, List.copyOf(crowds));
    }

    @Transactional(readOnly = true)
    public List<BannerInfo> findUpdatedBetween(
            LocalDateTime windowStart, LocalDateTime windowEnd) {
        return bannerInfoService.findUpdatedBetween(windowStart, windowEnd);
    }

    public record BannerSnapshot(BannerInfo banner, List<BannerCrowd> crowds) {
    }
}
