package com.bannerdeliver.common.constant;

/**
 * Banner 投放缓存 Key 模板。
 */
public final class BannerRedisConstant {

    public static final String DATE_CACHE_KEY = "product:%s:date:%s";
    public static final String AUDIENCE_BUCKET_KEY =
            "banner:audience:%s:%s:bucket:%s";
    public static final String CONSUME_WINDOW_KEY = "mq:consume:%s";
    // 同一 Banner 的元数据使用相同 Redis Cluster hash tag，供 Lua 原子更新。
    public static final String DATE_KEY_INDEX = "banner:date-keys:{%s}";
    public static final String VERSION_KEY = "banner:version:{%s}";

    private BannerRedisConstant() {
    }
}
