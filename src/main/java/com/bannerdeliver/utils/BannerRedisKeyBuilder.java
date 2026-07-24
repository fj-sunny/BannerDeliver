package com.bannerdeliver.utils;

import com.bannerdeliver.common.constant.BannerRedisConstant;
import com.bannerdeliver.domain.dto.BannerDateCacheKey;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class BannerRedisKeyBuilder {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;
    private static final Pattern DATE_CACHE_KEY_PATTERN =
            Pattern.compile("^product:(\\d+):date:(\\d{8})$");

    public String dateCacheKey(Long productId, LocalDate date) {
        return BannerRedisConstant.DATE_CACHE_KEY.formatted(
                productId, DATE_FORMATTER.format(date));
    }

    public String audienceBucketKey(Long bannerId, String audienceBatch, int bucketIndex) {
        return BannerRedisConstant.AUDIENCE_BUCKET_KEY.formatted(
                bannerId, audienceBatch, bucketIndex);
    }

    public String consumeWindowKey(String window) {
        return BannerRedisConstant.CONSUME_WINDOW_KEY.formatted(window);
    }

    public String dateKeyIndex(Long bannerId) {
        return BannerRedisConstant.DATE_KEY_INDEX.formatted(bannerId);
    }

    public String versionKey(Long bannerId) {
        return BannerRedisConstant.VERSION_KEY.formatted(bannerId);
    }

    public Optional<BannerDateCacheKey> parseDateCacheKey(String redisKey) {
        Matcher matcher = DATE_CACHE_KEY_PATTERN.matcher(redisKey);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new BannerDateCacheKey(
                    Long.valueOf(matcher.group(1)),
                    LocalDate.parse(matcher.group(2), DATE_FORMATTER)));
        } catch (NumberFormatException | DateTimeParseException exception) {
            return Optional.empty();
        }
    }
}
