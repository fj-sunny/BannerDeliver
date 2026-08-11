package com.bannerdeliver.utils;

import java.util.ArrayList;
import java.util.List;

/** 所有时间均使用 Unix 毫秒进行计算。 */
public final class BannerTimeUtils {

    public static final Long DAY_MILLIS = 86_400_000L;

    private BannerTimeUtils() {
    }

    /** 根据固定时区偏移计算业务日零点的 Unix 毫秒。 */
    public static Long startOfDay(Long time, Long zoneOffsetMillis) {
        if (time == null || zoneOffsetMillis == null) {
            throw new IllegalArgumentException("time and zoneOffsetMillis must not be null");
        }
        return Math.floorDiv(time + zoneOffsetMillis, DAY_MILLIS)
                * DAY_MILLIS - zoneOffsetMillis;
    }

    /** 返回起止时间覆盖的全部业务日零点毫秒。 */
    public static List<Long> inclusiveDates(
            Long beginTime, Long endTime, Long zoneOffsetMillis) {
        if (beginTime == null || endTime == null || endTime < beginTime) {
            throw new IllegalArgumentException(
                    "beginTime and endTime must be valid and ordered");
        }
        Long beginDate = startOfDay(beginTime, zoneOffsetMillis);
        Long endDate = startOfDay(endTime, zoneOffsetMillis);
        List<Long> dates = new ArrayList<>();
        for (Long date = beginDate; date <= endDate; date += DAY_MILLIS) {
            dates.add(date);
        }
        return List.copyOf(dates);
    }
}
