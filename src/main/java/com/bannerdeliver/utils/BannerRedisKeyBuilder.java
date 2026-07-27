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

/** Banner 相关 Redis Key 的构建与解析工具。 */
@Component
public class BannerRedisKeyBuilder {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;
    private static final Pattern DATE_CACHE_KEY_PATTERN =
            Pattern.compile("^product:(\\d+):date:(\\d{8})$");

    /** 构建日期 Hash Key：product:{productId}:date:{yyyyMMdd}。 */
    public String dateCacheKey(Long productId, LocalDate date) {
        return BannerRedisConstant.DATE_CACHE_KEY.formatted(
                productId, DATE_FORMATTER.format(date));
    }

    /** 构建人群包分桶 Key：banner:audience:{bannerId}:{audienceBatch}:bucket:{index}。 */
    public String audienceBucketKey(Long bannerId, String audienceBatch, int bucketIndex) {
        return BannerRedisConstant.AUDIENCE_BUCKET_KEY.formatted(
                bannerId, audienceBatch, bucketIndex);
    }

    /** 构建 Kafka 消费对账窗口 Key：mq:consume:{yyyyMMddHHmm}。 */
    public String consumeWindowKey(String window) {
        return BannerRedisConstant.CONSUME_WINDOW_KEY.formatted(window);
    }

    /** 从日期 Hash Key 反解析出 productId 和 date，格式不匹配时返回 empty。 */
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
