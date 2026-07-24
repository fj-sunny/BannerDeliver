package com.bannerdeliver.utils;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AudienceBucketCalculatorTest {

    private final AudienceBucketCalculator calculator = new AudienceBucketCalculator();

    @Test
    void shouldRoundBucketCountUp() {
        assertThat(calculator.bucketCount(0, 50000)).isZero();
        assertThat(calculator.bucketCount(1, 50000)).isEqualTo(1);
        assertThat(calculator.bucketCount(50000, 50000)).isEqualTo(1);
        assertThat(calculator.bucketCount(50001, 50000)).isEqualTo(2);
    }

    @Test
    void shouldAlwaysProduceValidStableBucketIndex() {
        int first = calculator.bucketIndex("user-with-negative-capable-hash", 20);
        int second = calculator.bucketIndex("user-with-negative-capable-hash", 20);

        assertThat(first).isBetween(0, 19).isEqualTo(second);
    }

    @Test
    void shouldRejectZeroBucketCount() {
        assertThatThrownBy(() -> calculator.bucketIndex("1001", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
