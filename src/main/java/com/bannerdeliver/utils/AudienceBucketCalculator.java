package com.bannerdeliver.utils;

import org.springframework.stereotype.Component;

@Component
public class AudienceBucketCalculator {

    public int bucketCount(long userCount, int targetBucketSize) {
        if (userCount <= 0) {
            return 0;
        }
        return Math.toIntExact((userCount + targetBucketSize - 1L) / targetBucketSize);
    }

    /**
     * String.hashCode 在 Java 中是确定性算法，floorMod 可处理负哈希值。
     */
    public int bucketIndex(String userId, int bucketCount) {
        if (bucketCount <= 0) {
            throw new IllegalArgumentException("bucketCount must be greater than zero");
        }
        return Math.floorMod(userId.hashCode(), bucketCount);
    }
}
