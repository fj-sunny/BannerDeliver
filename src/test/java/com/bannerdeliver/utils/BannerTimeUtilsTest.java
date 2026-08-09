package com.bannerdeliver.utils;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BannerTimeUtilsTest {

    @Test
    void shouldReturnInclusiveDateRange() {
        assertThat(BannerTimeUtils.inclusiveDates(
                1784512800000L, 1784682000000L, 28_800_000L))
                .isEqualTo(List.of(
                        1784476800000L,
                        1784563200000L,
                        1784649600000L));
    }

    @Test
    void shouldRejectInvalidRange() {
        assertThatThrownBy(() -> BannerTimeUtils.inclusiveDates(
                1784685600000L, 1784511000000L, 28_800_000L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
