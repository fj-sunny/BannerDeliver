package com.bannerdeliver.service; // 当前类所在包

import com.bannerdeliver.config.BannerProperties; // 引入 Banner 配置属性
import com.bannerdeliver.domain.dto.BannerDateCacheKey; // 引入本地缓存 Key（productId+date）
import com.bannerdeliver.domain.dto.BannerEventType; // 引入 Kafka 事件类型枚举
import com.bannerdeliver.domain.dto.BannerRuntimeDTO; // 引入 Redis/L1 中的 Banner 运行时快照
import com.bannerdeliver.domain.po.BannerCrowd; // 引入 MySQL 人群包实体
import com.bannerdeliver.domain.po.BannerInfo; // 引入 MySQL Banner 配置实体
import com.bannerdeliver.utils.AudienceBucketCalculator; // 引入人群分桶计算器
import com.bannerdeliver.utils.AudienceUserListCodec; // 引入人群 userList 编解码器
import com.bannerdeliver.utils.BannerCacheExpiryCalculator; // 引入缓存过期时间计算器
import com.bannerdeliver.utils.BannerTimeUtils; // 引入业务日时间工具
import com.fasterxml.jackson.core.JsonProcessingException; // 引入 JSON 处理异常
import com.fasterxml.jackson.databind.ObjectMapper; // 引入 Jackson ObjectMapper
import com.google.common.cache.Cache; // 引入 Guava Cache 接口
import lombok.RequiredArgsConstructor; // 引入 Lombok 构造器注入注解
import lombok.extern.slf4j.Slf4j; // 引入 Lombok 日志注解
import org.springframework.data.redis.core.StringRedisTemplate; // 引入 Redis 字符串模板
import org.springframework.stereotype.Service; // 引入 Spring Service 注解
import org.springframework.transaction.annotation.Transactional; // 引入事务注解

import java.util.ArrayList; // 引入可变列表实现
import java.util.HashMap; // 引入哈希表实现
import java.util.HashSet; // 引入哈希集合实现
import java.util.LinkedHashMap; // 引入保序 Map 实现
import java.util.List; // 引入 List 接口
import java.util.Map; // 引入 Map 接口
import java.util.Objects; // 引入 Objects 工具类
import java.util.Optional; // 引入 Optional 容器
import java.util.Set; // 引入 Set 接口
import java.util.concurrent.ExecutionException; // 引入异步/缓存加载异常
import java.util.concurrent.TimeUnit; // 引入时间单位枚举

/**
 * Banner 多级缓存核心：查询走 Guava L1 → Redis L2；Kafka/对账触发时从 MySQL 只读刷新两级缓存。
 *
 * <p>MySQL 写库由运营端负责；本类根据已落库的最新数据新增 Redis audienceBatch，
 * 并切换 {@link BannerRuntimeDTO} 中的指针，不删除旧 batch Key。</p>
 */
@Slf4j // 自动生成日志对象 log
@Service // 注册为 Spring 业务 Bean
@RequiredArgsConstructor // 为 final 字段生成构造器，实现依赖注入
public class BannerCacheService { // Banner 多级缓存服务类开始

    private final Cache<BannerDateCacheKey, List<BannerRuntimeDTO>> localCache; // Guava L1 本地缓存
    private final StringRedisTemplate redisTemplate; // Redis 客户端（字符串序列化）
    private final ObjectMapper objectMapper; // JSON 序列化/反序列化工具
    private final AudienceBucketCalculator bucketCalculator; // 计算 bucketCount / bucketIndex
    private final BannerCacheExpiryCalculator expiryCalculator; // 计算绝对过期时间与抖动
    private final BannerProperties properties; // banner.* 配置项
    private final AudienceUserListCodec userListCodec; // 解码 MySQL user_list
    private final BannerInfoService bannerInfoService; // 只读查询 banner_info
    private final BannerCrowdService bannerCrowdService; // 只读查询 banner_crowd

    /** L1 未命中回源 Redis；并发未命中由 Guava Cache.get 合并为一次加载。 */
    public List<BannerRuntimeDTO> getBanners(Long productId, Long date) { // 按商品+业务日查询 Banner 列表
        BannerDateCacheKey cacheKey = new BannerDateCacheKey(productId, date); // 组装 L1 缓存 Key
        try { // 捕获 Guava 加载过程中的包装异常
            return localCache.get(cacheKey, () -> List.copyOf(loadFromRedis(productId, date))); // 命中直接返回；未命中则回源 Redis 并写入 L1
        } catch (ExecutionException exception) { // 加载回调抛出异常时进入这里
            throw new IllegalStateException( // 转换为运行时异常向上抛出
                    "Unable to load banner cache for " + cacheKey, exception.getCause()); // 附带原始原因，便于排查
        } // try-catch 结束
    } // getBanners 结束

    /**
     * 判断 userId 是否属于该 Banner 的人群包。
     * bucketIndex 由 userId.hashCode % bucketCount 计算；audienceBatch 用于拼 Redis Key 批次。
     */
    public boolean isAudienceMember(BannerRuntimeDTO runtime, String userId) { // 判断用户是否在人群包内
        if (runtime.getBucketCount() == null || runtime.getBucketCount() <= 0) { // bucketCount 为空或 <=0 表示无有效人群
            return false; // 不在人群包（或无限制场景按当前逻辑直接否）
        } // if 结束
        int bucketIndex = bucketCalculator.bucketIndex(userId, runtime.getBucketCount()); // 计算该用户落在哪个桶
        String audienceKey = "banner:audience:%s:%s:bucket:%s".formatted( // 拼出 Redis 人群 Set Key
                runtime.getBannerId(), runtime.getAudienceBatch(), bucketIndex); // 使用 bannerId + 当前 batch + 桶号
        return Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(audienceKey, userId)); // SISMEMBER 判断是否成员；避免 NPE 用 TRUE.equals
    } // isAudienceMember 结束

    /**
     * 从 MySQL 刷新一个 Banner 的 Redis 与本地缓存。
     *
     * <p>人群变更（{@code AUDIENCE_UPDATE}/{@code FULL_UPDATE}）在 Redis 侧是「只新增」：
     * 先把 MySQL 最新人群写入新 {@code audienceBatch=eventId} 的全部 bucket，
     * 全部成功后再 HSET 日期 Hash 中的 {@link BannerRuntimeDTO}，切换
     * {@code audienceBatch}/{@code bucketCount}。旧 batch Key 不删除、不改 TTL，到期自动消失。</p>
     */
    @Transactional(readOnly = true) // 只读事务，保证读 MySQL 一致性
    public void refreshBannerCache( // 按事件刷新单个 Banner 的缓存
            Long bannerId, String eventId, BannerEventType eventType) { // bannerId 定位；eventId 可作新 audienceBatch；eventType 决定刷新范围
        // 1. 根据 bannerId 从 MySQL 读取最新 Banner / 人群源数据。
        BannerInfo banner = bannerInfoService.findById(bannerId); // 从 MySQL 读最新 Banner 配置
        if (banner == null) { // Banner 已不存在（可能被运营端删除）
            return; // 无需刷新，直接结束
        } // if 结束

        // 2. 按最新投放范围确定要覆盖的日期 Hash，并算出人群包绝对过期基准。
        Set<BannerDateCacheKey> affected = new HashSet<>(); // 收集受影响的 L1 Key，稍后统一 invalidate
        List<Long> dates = BannerTimeUtils.inclusiveDates( // 计算投放期内所有业务日零点毫秒
                banner.getBeginTime(), // 投放开始时间
                banner.getEndTime(), // 投放结束时间
                properties.getCache().getRedis().getZoneOffsetMillis()); // 业务时区偏移（默认 UTC+8）
        Map<String, Long> dateKeys = new LinkedHashMap<>(); // dateKey -> 该 Key 的绝对过期毫秒（保序便于调试）
        for (Long date : dates) { // 遍历每个业务日
            dateKeys.put( // 记录 Redis 日期 Hash Key 及其过期点
                    "product:%s:date:%s".formatted(banner.getProductId(), date), // 拼 product:{productId}:date:{date}
                    expiryCalculator.dateCacheExpireAt(date)); // 过期点 = 业务日 + N 天
            affected.add(new BannerDateCacheKey(banner.getProductId(), date)); // 同步记录需要失效的 L1 Key
        } // for 结束
        // 人群 Set TTL 基准 = 投放期内所有日期 Hash 过期点的最大值（业务日 + N 天），
        // 写入时再叠加 Key 级稳定抖动；切换新 batch 时不会去碰旧 Key。
        Long audienceExpiry = dateKeys.values().stream().max(Long::compareTo).orElseThrow(); // 取最晚过期点作为人群包 TTL 基准

        String audienceBatch = null; // 将要写入 Runtime 的人群批次号，先置空
        Integer existingBucketCount = null; // BANNER_UPDATE 时尝试复用的已有桶数
        if (eventType == BannerEventType.BANNER_UPDATE) { // 仅 Banner 基础信息变更时，尽量不重写人群包
            for (String dateKey : dateKeys.keySet()) { // 在日期 Hash 中查找已有 Runtime
                Object current = redisTemplate.opsForHash().get( // HGET 读取该 bannerId 字段
                        dateKey, String.valueOf(bannerId)); // field 名是 bannerId 字符串
                if (current != null) { // 找到已有缓存快照
                    BannerRuntimeDTO runtime = deserialize(String.valueOf(current)); // JSON -> Runtime 对象
                    audienceBatch = runtime.getAudienceBatch(); // 复用旧 audienceBatch
                    existingBucketCount = runtime.getBucketCount(); // 复用旧 bucketCount
                    break; // 找到一份即可，不必扫完所有日期
                } // if 结束
            } // for 结束
        } // if BANNER_UPDATE 结束
        boolean refreshAudience = eventType != BannerEventType.BANNER_UPDATE // 非 BANNER_UPDATE（如 AUDIENCE/FULL）必须刷新人群
                || audienceBatch == null // 或 Redis 里没有可复用的 batch
                || existingBucketCount == null; // 或 Redis 里没有可复用的桶数
        if (refreshAudience) { // 需要写入新人群批次
            // 新 batch：只 SADD 新 Key，绝不 DEL 旧 audienceBatch。
            audienceBatch = eventId; // 用 Kafka eventId 作为新 audienceBatch
        } // if 结束
        int bucketCount = refreshAudience // 决定最终写入 Runtime 的 bucketCount
                ? writeAudienceVersion( // 需要刷新人群：写新 batch 全部分桶
                        bannerId, // 目标 Banner
                        audienceBatch, // 新批次号
                        bannerCrowdService.findByBannerId(bannerId), // 从 MySQL 读最新人群分页
                        audienceExpiry) // 人群 Key 过期基准
                : existingBucketCount; // 不刷新人群：沿用旧桶数
        if (!refreshAudience) { // BANNER_UPDATE 且成功复用旧 batch
            // BANNER_UPDATE：复用已有 batch，仅续期人群 Key TTL。
            for (int bucketIndex = 0; bucketIndex < bucketCount; bucketIndex++) { // 遍历旧 batch 的每个桶
                String audienceKey = // 拼出该桶 Redis Key
                        "banner:audience:%s:%s:bucket:%s".formatted( // Key 格式固定
                                bannerId, audienceBatch, bucketIndex); // bannerId + 旧 batch + 桶下标
                expireAt( // 重新设置过期时间（续期）
                        audienceKey, // 目标人群 Key
                        expiryCalculator.withStableJitter(audienceExpiry, audienceKey)); // 基准过期 + 稳定抖动
            } // for 结束
        } // if 结束
        // 新 batch 写完后再切换指针；读路径立刻看到新 audienceBatch / bucketCount。
        BannerRuntimeDTO runtime = BannerRuntimeDTO.builder() // 构建要写入日期 Hash 的 Runtime JSON 对象
                .bannerId(banner.getBannerId()) // Banner 主键（也是 Hash field）
                .productId(banner.getProductId()) // 所属商品
                .url(banner.getUrl()) // 资源/跳转地址
                .beginTime(banner.getBeginTime()) // 投放开始毫秒
                .endTime(banner.getEndTime()) // 投放结束毫秒
                .status(banner.getStatus()) // 启用/停用状态
                .bucketCount(bucketCount) // 人群分桶总数
                .audienceBatch(audienceBatch) // 当前生效的人群批次号（指针）
                .updateTime(banner.getUpdateTime()) // 对齐 MySQL update_time，供对账比较
                .build(); // 完成构建
        String bannerField = String.valueOf(bannerId); // Hash field 名：bannerId 字符串
        String runtimeJson = serialize(runtime); // Runtime 对象序列化为 JSON 字符串
        dateKeys.forEach((dateKey, expireAt) -> { // 对投放期内每个日期 Hash 执行写入
            redisTemplate.opsForHash().put(dateKey, bannerField, runtimeJson); // HSET：覆盖该 Banner 的 Runtime
            expireAt(dateKey, expireAt); // 设置/刷新日期 Hash 的 TTL
        }); // forEach 结束

        // 3. Redis 同步完成后，删除受影响的本机 LocalCache，下次查询再回源 Redis。
        localCache.invalidateAll(affected); // 删除本机受影响 L1 Key（不是 put 覆盖）

        log.info("Refreshed banner cache: bannerId={}, batch={}, bucketCount={}, dates={}", // 打印刷新结果
                banner.getBannerId(), audienceBatch, bucketCount, dates.size()); // 关键关键参数
    } // refreshBannerCache 结束

    /** 查询对账窗口 [windowStart, windowEnd) 内 update_time 变更的 Banner。 */
    @Transactional(readOnly = true) // 只读事务
    public List<BannerInfo> findUpdatedBetween( // 供定时对账查询窗口内变更 Banner
            Long windowStart, Long windowEnd) { // 窗口左闭右开：[start, end)
        return bannerInfoService.findUpdatedBetween(windowStart, windowEnd); // 委托 InfoService 查 MySQL
    } // findUpdatedBetween 结束

    /** 检查每个应覆盖日期的 Redis Runtime 是否存在且 updateTime 不早于 MySQL。 */
    public boolean hasLatestRuntime(BannerInfo dbBanner) { // 判断 Redis 是否已是 MySQL 最新版本
        for (Long date : BannerTimeUtils.inclusiveDates( // 遍历该 Banner 投放期每个业务日
                dbBanner.getBeginTime(), // MySQL 开始时间
                dbBanner.getEndTime(), // MySQL 结束时间
                properties.getCache().getRedis().getZoneOffsetMillis())) { // 时区偏移
            Optional<BannerRuntimeDTO> runtime = findBannerRuntime( // 读 Redis 中该日该 Banner 的 Runtime
                    dbBanner.getProductId(), date, dbBanner.getBannerId()); // 用商品、日期、bannerId 定位
            if (runtime.isEmpty() || runtime.get().getUpdateTime() == null) { // 缺失或无 updateTime
                return false; // 判定未同步到最新
            } // if 结束
            if (runtime.get().getUpdateTime() < dbBanner.getUpdateTime()) { // Redis 版本早于 MySQL
                return false; // 判定过期，需要对账修复
            } // if 结束
        } // for 结束
        return true; // 所有日期都存在且版本不落后
    } // hasLatestRuntime 结束

    /** 从 Redis 日期 Hash 读取全部 Banner Runtime 并反序列化。 */
    private List<BannerRuntimeDTO> loadFromRedis(Long productId, Long date) { // L1 未命中时的 Redis 回源
        String dateKey = "product:%s:date:%s".formatted(productId, date); // 拼日期 Hash Key
        return redisTemplate.opsForHash().values(dateKey).stream() // 取出该日所有 Banner JSON
                .filter(Objects::nonNull) // 过滤空值
                .map(String::valueOf) // 统一转成字符串
                .map(this::deserialize) // JSON 反序列化为 Runtime
                .toList(); // 收集为不可变 List（Java 16+）
    } // loadFromRedis 结束

    /** 读取单个 Banner 在指定商品+日期的 Runtime，不存在或 JSON 非法时返回 empty。 */
    private Optional<BannerRuntimeDTO> findBannerRuntime( // 对账时读取单个 Runtime
            Long productId, Long date, Long bannerId) { // 定位三元组
        Object runtimeJson = redisTemplate.opsForHash().get( // HGET 指定 field
                "product:%s:date:%s".formatted(productId, date), // 日期 Hash Key
                String.valueOf(bannerId)); // field = bannerId
        if (runtimeJson == null) { // Redis 中不存在
            return Optional.empty(); // 返回空
        } // if 结束
        try { // 反序列化可能因脏数据失败
            return Optional.of(deserialize(String.valueOf(runtimeJson))); // 成功则包装为 Optional
        } catch (IllegalStateException exception) { // JSON 非法
            return Optional.empty(); // 对账侧视为缺失，触发修复
        } // try-catch 结束
    } // findBannerRuntime 结束

    /**
     * 将 MySQL 最新人群按 bucketIndex 写入「新」Redis Set，返回 bucketCount。
     *
     * <p>只新增 {@code banner:audience:{bannerId}:{audienceBatch}:bucket:*}，不删除任何旧 batch。
     * TTL = {@code baseExpireAt}（投放期最晚日期 Hash 过期点）+ Key 稳定抖动。</p>
     */
    private int writeAudienceVersion( // 写入新 audienceBatch 的全部分桶
            Long bannerId, String audienceBatch, List<BannerCrowd> crowds, Long baseExpireAt) { // 人群源数据与过期基准
        long userCount = crowds.stream() // 统计总用户数
                .map(BannerCrowd::getUserList) // 取每页 user_list 原始串
                .map(userListCodec::decode) // 解码为 List<userId>
                .mapToLong(List::size) // 取每页人数
                .sum(); // 求和得到总人数
        int bucketCount = bucketCalculator.bucketCount( // 按目标桶大小计算桶数
                userCount, properties.getAudience().getTargetBucketSize()); // ceil(userCount / targetBucketSize)
        if (bucketCount == 0) { // 空人群包
            return 0; // 不写任何 Redis Set，直接返回
        } // if 结束
        int writeBatchSize = properties.getAudience().getRedisWriteBatchSize(); // 单次 SADD 批量上限
        Map<Integer, List<String>> buffers = new HashMap<>(); // 按桶缓存待写入 userId
        for (BannerCrowd crowd : crowds) { // 遍历每一页人群
            for (String userId : userListCodec.decode(crowd.getUserList())) { // 解码并遍历每个用户
                int bucketIndex = bucketCalculator.bucketIndex(userId, bucketCount); // 计算该用户所属桶
                List<String> buffer = buffers.computeIfAbsent( // 取/建该桶的写缓冲
                        bucketIndex, ignored -> new ArrayList<>(writeBatchSize)); // 预分配容量
                buffer.add(userId); // 用户加入缓冲
                if (buffer.size() >= writeBatchSize) { // 缓冲满了就刷一次 Redis
                    flushAudienceMembers(bannerId, audienceBatch, bucketIndex, buffer, baseExpireAt); // 批量 SADD + 设 TTL
                } // if 结束
            } // 内层 for 结束
        } // 外层 for 结束
        buffers.forEach((bucketIndex, buffer) -> // 把各桶剩余缓冲刷完
                flushAudienceMembers(bannerId, audienceBatch, bucketIndex, buffer, baseExpireAt)); // 尾批写入
        return bucketCount; // 返回本 batch 的桶总数，供 Runtime 记录
    } // writeAudienceVersion 结束

    /**
     * 批量 SADD 一个新 batch 桶内的 userId，并设置绝对过期时间（含稳定抖动）。
     * 不清理同 banner 下其它 audienceBatch 的 Key。
     */
    private void flushAudienceMembers( // 把一个桶缓冲刷进 Redis
            Long bannerId, String audienceBatch, int bucketIndex, // 定位到具体 bucket Key
            List<String> userIds, Long baseExpireAt) { // 待写入用户与过期基准
        if (userIds.isEmpty()) { // 没有用户可写
            return; // 直接返回，避免无效 Redis 调用
        } // if 结束
        String audienceKey = "banner:audience:%s:%s:bucket:%s".formatted( // 拼人群桶 Key
                bannerId, audienceBatch, bucketIndex); // 新 batch 的桶 Key（旧 batch 不动）
        redisTemplate.opsForSet().add(audienceKey, userIds.toArray(String[]::new)); // SADD 批量加入成员
        expireAt(audienceKey, expiryCalculator.withStableJitter(baseExpireAt, audienceKey)); // 设置带抖动的 TTL
        userIds.clear(); // 清空缓冲，便于复用该 List
    } // flushAudienceMembers 结束

    private void expireAt(String key, Long expireAt) { // 把绝对过期毫秒转成相对 TTL 并设置
        redisTemplate.expire( // 调用 Redis EXPIRE
                key, // 目标 Key
                Math.max(0L, expireAt - System.currentTimeMillis()), // 剩余毫秒；已过期则置 0
                TimeUnit.MILLISECONDS); // TTL 单位：毫秒
    } // expireAt 结束

    /** 将 Runtime 序列化为 JSON 字符串写入 Redis。 */
    private String serialize(BannerRuntimeDTO runtime) { // Runtime -> JSON
        try { // Jackson 可能抛受检异常
            return objectMapper.writeValueAsString(runtime); // 序列化成功返回字符串
        } catch (JsonProcessingException exception) { // 序列化失败
            throw new IllegalStateException("Unable to serialize BannerRuntimeDTO", exception); // 包装为非受检异常
        } // try-catch 结束
    } // serialize 结束

    /** 将 Redis 中的 JSON 反序列化为 Runtime，格式非法时抛 IllegalStateException。 */
    private BannerRuntimeDTO deserialize(String runtimeJson) { // JSON -> Runtime
        try { // Jackson 可能抛受检异常
            return objectMapper.readValue(runtimeJson, BannerRuntimeDTO.class); // 按目标类型反序列化
        } catch (JsonProcessingException exception) { // JSON 格式/字段不合法
            throw new IllegalStateException("Invalid BannerRuntimeJson in Redis", exception); // 包装为非受检异常
        } // try-catch 结束
    } // deserialize 结束

} // BannerCacheService 类结束
