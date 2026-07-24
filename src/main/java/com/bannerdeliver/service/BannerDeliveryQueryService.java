package com.bannerdeliver.service;

import com.bannerdeliver.cache.BannerRuntimeCache;
import com.bannerdeliver.cache.local.BannerLocalCache;
import com.bannerdeliver.domain.dto.BannerRuntimeDTO;
import com.bannerdeliver.utils.BannerDateTimeUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 单个日期内的 Banner 投放匹配服务。
 *
 * <p>先从 Guava/Redis 取得候选 Banner，再按状态、投放时间排序，最后通过 Redis
 * SISMEMBER 判断用户人群。当天与昨日兜底的编排由 {@link BannerDeliveryService} 负责。</p>
 */
@Service
@RequiredArgsConstructor
public class BannerDeliveryQueryService {

    private final BannerLocalCache localCache;
    private final BannerRuntimeCache runtimeCache;

    public List<BannerRuntimeDTO> findEligibleBanners(
            Long productId, String userId, LocalDateTime queryTime) {
        return eligibleCandidates(productId, queryTime)
                .filter(runtime -> runtimeCache.isAudienceMember(runtime, userId))
                .toList();
    }

    public Optional<BannerRuntimeDTO> findFirstEligibleBanner(
            Long productId, String userId, LocalDateTime queryTime) {
        return eligibleCandidates(productId, queryTime)
                .filter(runtime -> runtimeCache.isAudienceMember(runtime, userId))
                .findFirst();
    }

    private Stream<BannerRuntimeDTO> eligibleCandidates(
            Long productId, LocalDateTime queryTime) {
        return localCache.getBanners(productId, queryTime.toLocalDate()).stream()
                .filter(runtime -> Integer.valueOf(1).equals(runtime.getStatus()))
                .filter(runtime -> isWithinDeliveryTime(runtime, queryTime))
                .sorted(Comparator.comparing(BannerRuntimeDTO::getBannerId));
    }

    private boolean isWithinDeliveryTime(BannerRuntimeDTO runtime, LocalDateTime queryTime) {
        LocalDateTime beginTime = BannerDateTimeUtils.parseDateTime(
                runtime.getBeginTime(), "beginTime");
        LocalDateTime endTime = BannerDateTimeUtils.parseDateTime(
                runtime.getEndTime(), "endTime");
        return !queryTime.isBefore(beginTime) && !queryTime.isAfter(endTime);
    }
}
