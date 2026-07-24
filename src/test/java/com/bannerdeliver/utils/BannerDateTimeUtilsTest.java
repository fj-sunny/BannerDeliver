package com.bannerdeliver.utils;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BannerDateTimeUtilsTest {

    @Test
    void shouldReturnInclusiveDateRange() {
        assertThat(BannerDateTimeUtils.inclusiveDates(
                "2026-07-20 10:00:00", "2026-07-22 09:00:00"))
                .isEqualTo(List.of(
                        LocalDate.of(2026, 7, 20),
                        LocalDate.of(2026, 7, 21),
                        LocalDate.of(2026, 7, 22)));
    }

    @Test
    void shouldRejectInvalidRange() {
        assertThatThrownBy(() -> BannerDateTimeUtils.inclusiveDates(
                "2026-07-22 10:00:00", "2026-07-20 09:00:00"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
