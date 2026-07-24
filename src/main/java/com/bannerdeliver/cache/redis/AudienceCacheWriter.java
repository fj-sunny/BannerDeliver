package com.bannerdeliver.cache.redis;

import com.bannerdeliver.cache.support.AudienceUserListCodec;
import com.bannerdeliver.config.BannerProperties;
import com.bannerdeliver.domain.po.BannerCrowd;
import com.bannerdeliver.utils.AudienceBucketCalculator;
import com.bannerdeliver.utils.BannerRedisKeyBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Redis 人群包版本写入器。
 *
 * <p>先计算固定 bucketCount，再按 userId hash 写入版本化 Set。所有 Set 写完后，
 * 上层刷新服务才会切换 Banner Runtime 中的 audienceBatch，避免读到半成品。</p>
 */
@Component
@RequiredArgsConstructor
public class AudienceCacheWriter {

    private final AudienceUserListCodec userListCodec;
    private final AudienceBucketCalculator bucketCalculator;
    private final BannerRedisKeyBuilder keyBuilder;
    private final BannerRedisRepository redisRepository;
    private final BannerProperties properties;

    /**
     * 将完整 MySQL 人群包写成一个新的 Redis 版本。
     *
     * @return 写入和查询必须共同使用的 bucketCount
     */
    public int writeAudienceVersion(Long bannerId, String audienceBatch,
                                    List<BannerCrowd> crowds, Instant baseExpireAt) {
        long userCount = crowds.stream()
                .map(BannerCrowd::getUserList)
                .map(userListCodec::decode)
                .mapToLong(List::size)
                .sum();
        int bucketCount = bucketCalculator.bucketCount(
                userCount, properties.getAudience().getTargetBucketSize());
        if (bucketCount == 0) {
            return 0;
        }

        int writeBatchSize = properties.getAudience().getRedisWriteBatchSize();
        Map<Integer, List<String>> buffers = new HashMap<>();
        for (BannerCrowd crowd : crowds) {
            for (String userId : userListCodec.decode(crowd.getUserList())) {
                int bucketIndex = bucketCalculator.bucketIndex(userId, bucketCount);
                List<String> buffer = buffers.computeIfAbsent(
                        bucketIndex, ignored -> new ArrayList<>(writeBatchSize));
                buffer.add(userId);
                if (buffer.size() >= writeBatchSize) {
                    flush(bannerId, audienceBatch, bucketIndex, buffer, baseExpireAt);
                }
            }
        }
        buffers.forEach((bucketIndex, buffer) ->
                flush(bannerId, audienceBatch, bucketIndex, buffer, baseExpireAt));
        return bucketCount;
    }

    private void flush(Long bannerId, String audienceBatch, int bucketIndex,
                       List<String> userIds, Instant baseExpireAt) {
        if (userIds.isEmpty()) {
            return;
        }
        String audienceKey = keyBuilder.audienceBucketKey(
                bannerId, audienceBatch, bucketIndex);
        redisRepository.addAudienceMembers(audienceKey, userIds, baseExpireAt);
        userIds.clear();
    }
}
