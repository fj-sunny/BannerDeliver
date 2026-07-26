package com.bannerdeliver.utils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.stream.Stream;

/** Banner 业务时间字符串的解析与日期范围计算。 */
public final class BannerDateTimeUtils {

    public static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private BannerDateTimeUtils() {
    }

    /** 将 yyyy-MM-dd HH:mm:ss 格式字符串解析为 LocalDateTime，失败时抛出 IllegalArgumentException。 */
    public static LocalDateTime parseDateTime(String value, String fieldName) {
        try {
            return LocalDateTime.parse(value, DATE_TIME_FORMATTER);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException(
                    fieldName + " must use yyyy-MM-dd HH:mm:ss: " + value,
                    exception);
        }
    }

    /** 根据 beginTime 和 endTime 计算闭区间内的全部业务日期列表。 */
    public static List<LocalDate> inclusiveDates(String beginTime, String endTime) {
        LocalDate beginDate = parseDateTime(beginTime, "beginTime").toLocalDate();
        LocalDate endDate = parseDateTime(endTime, "endTime").toLocalDate();
        if (endDate.isBefore(beginDate)) {
            throw new IllegalArgumentException("endTime must not be before beginTime");
        }
        try (Stream<LocalDate> dates = beginDate.datesUntil(endDate.plusDays(1))) {
            return dates.toList();
        }
    }
}
