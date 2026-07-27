package com.bannerdeliver.common.constant;

/** Banner 投放缓存 Redis Key 模板常量。 */
public final class BannerRedisConstant {

    /** 日期 Hash：product:{productId}:date:{yyyyMMdd}。 */
    public static final String DATE_CACHE_KEY = "product:%s:date:%s";
    /** 人群包分桶 Set：banner:audience:{bannerId}:{audienceBatch}:bucket:{index}。 */
    public static final String AUDIENCE_BUCKET_KEY =
            "banner:audience:%s:%s:bucket:%s";
    /** Kafka 消费对账窗口 Set：mq:consume:{yyyyMMddHHmm}。 */
    public static final String CONSUME_WINDOW_KEY = "mq:consume:%s";

    /** 工具类禁止实例化。 */
    private BannerRedisConstant() {
    }
}
