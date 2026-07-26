package com.bannerdeliver.utils;

import org.springframework.stereotype.Component;

/** 人群包用户分桶数量与 bucketIndex 的计算。 */
@Component
public class AudienceBucketCalculator {

    /** 根据用户总数和目标单桶人数计算桶数，空人群包返回 0。 */
    public int bucketCount(long userCount, int targetBucketSize) {
        if (userCount <= 0) {
            return 0;
        }
        return Math.toIntExact((userCount + targetBucketSize - 1L) / targetBucketSize);
    }

    /**
     * 根据 userId 哈希值计算其所属桶索引。
     * String.hashCode 在 Java 中是确定性算法，floorMod 可处理负哈希值。
     */
    public int bucketIndex(String userId, int bucketCount) {
        if (bucketCount <= 0) {
            throw new IllegalArgumentException("bucketCount must be greater than zero");
        }
        return Math.floorMod(userId.hashCode(), bucketCount);
    }
}
