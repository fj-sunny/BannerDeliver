# BannerDeliver

Banner 投放系统后端骨架，基于 Java 17、Spring Boot、MyBatis-Plus 和 MySQL。

## 核心流程

项目只保留四条主链路，代码集中在少数几个 Service 中：

```text
1. 用户查询   Controller → BannerDeliveryService → BannerCacheService（L1 Guava + L2 Redis）
2. 数据变更   BannerInfo/CrowdService → MySQL 事务 → Kafka Producer
3. 缓存刷新   Kafka Consumer → BannerCacheService.refreshBannerCache
4. 定时对账   BannerReconciliationService → BannerCacheService.refreshBannerCache
```

兜底（历史日期、静态默认 Banner）只在 `BannerDeliveryService` 末尾补充，不是主链路。

## 当前结构

```text
src/main/java/com/bannerdeliver
├── App.java
├── controller/BannerDeliveryController.java   # 用户查询 HTTP 入口
├── service/
│   ├── BannerDeliveryService.java             # 用户查询 + 兜底编排
│   ├── BannerCacheService.java                # 多级缓存 + Redis + MySQL 刷新（核心）
│   ├── BannerInfoService.java                 # Banner 配置写库 + 发 Kafka
│   └── BannerCrowdService.java                # 人群包写库 + 发 Kafka
├── kafka/
│   ├── producer/BannerEventProducer.java
│   └── consumer/BannerEventConsumer.java
├── schedule/BannerReconciliationService.java  # 定时对账 + 补偿修复
├── mapper/、domain/、config/、utils/、common/、exception/
```

`BannerCacheService` 统一承担：Guava L1、Redis L2 读写、人群包分桶写入、版本幂等、消费窗口记录。

配置更新、下线或人群包替换先完成 MySQL 事务，再由 `BannerEventProducer` 发送 Kafka；
消费端由 `BannerEventConsumer` 调用 `BannerCacheService` 从 MySQL 重读并刷新 Redis 和本机 L1。

## 字段映射

| MySQL 字段 | Java 类型 | 说明 |
| --- | --- | --- |
| `BIGINT` | `Long` | 主键及关联 ID |
| `INT` | `Integer` | 人群包页码/桶号 |
| `TINYINT` | `Integer` | Banner 状态 |
| `BIGINT` | `Long` | 所有业务时间统一使用 Unix 毫秒 |
| `BLOB` | `String` | `user_list` 通过 UTF-8 TypeHandler 读写 |

`StringBlobTypeHandler` 负责把 Java `String` 按 UTF-8 转成字节写入 BLOB，并在查询时按
UTF-8 还原，避免依赖 JDBC 驱动的隐式类型转换。

`user_list` 推荐保存 JSON 数组，例如 `["1001","1002"]`。当前解码器也兼容逗号或
换行分隔的历史格式。

## 多级缓存

| 层级 | 实现 | 说明 |
| --- | --- | --- |
| L1 | Guava `Cache`（内嵌于 `BannerCacheService`） | Key=`productId+date`，TTL 30s |
| L2 | Redis Hash | `product:{productId}:date:{业务日零点毫秒}` 存 Banner JSON |
| 人群 | Redis Set | 分桶 `SISMEMBER` 判断用户归属 |

查询：`BannerCacheService.getBanners` → 过滤 status/时间 → `isAudienceMember`。
刷新后立即从 Redis 回读并覆盖本机 L1；其他实例最终一致仍依赖 L1 TTL。

## Redis 缓存

| 用途 | Key | 类型 |
| --- | --- | --- |
| Banner 日期缓存 | `product:{productId}:date:{业务日零点毫秒}` | Hash |
| 人群包分桶 | `banner:audience:{bannerId}:{audienceBatch}:bucket:{index}` | Set |

Redis 只保存 Banner 日期 Hash 和人群包 Set。

- 日期 Hash 在业务日期加两天的 `00:00` 绝对过期。
- 业务日按 `BANNER_CACHE_ZONE_OFFSET_MILLIS` 固定偏移计算，默认 `28800000`（UTC+8）。
- 人群包按整个 Banner 日期范围的最晚过期时间设置 TTL，并加入 Key 派生的稳定抖动。
- `bucketCount = ceil(userCount / targetBucketSize)`；空人群包为 `0`。
- `bucketIndex = floorMod(userId.hashCode(), bucketCount)`。
- 消费者只按 MySQL 最新投放范围新增或覆盖日期 Hash，不记录旧商品和旧日期范围。
- 新 `audienceBatch` 全部写完后才写入日期 Hash；旧批次等待 TTL 自动删除。
- Redis 读写统一在 `BannerCacheService` 中完成。

## Kafka 刷新

- Topic：`banner-delivery-event`，可通过 `BANNER_EVENT_TOPIC` 修改。
- Producer 始终以 `bannerId` 字符串作为 message key。
- Consumer 使用手动提交 offset，Redis 和本地缓存更新成功后才确认消息。
- 处理失败时不提交 offset，并按固定间隔重新投递。
- Consumer 只使用消息定位 Banner，`eventTime` 为 Unix 毫秒；业务字段从 MySQL 的 `banner_info`、`banner_crowd`
  重新读取。
- 重复事件每次都从 MySQL 读取最新数据并全量刷新。

Kafka Topic 需要在部署环境预先创建。更新 MySQL 成功后，业务服务调用
`BannerEventProducer.sendAfterCommit(event)` 发送事件。`BannerInfoService`
和 `BannerCrowdService` 已接入该生产者。

### 重复消费与幂等

系统不额外保存“已消费 eventId”来阻止重复消息，而是让每一步都可以安全重试：

- 人群变更消息使用 `eventId` 作为新的 `audienceBatch`。
- Kafka 消息包含 `eventId`、`bannerId`、`eventType`、`eventTime`，不携带旧商品和旧日期范围。
- `BANNER_UPDATE` 更新 Banner 日期 Hash；`AUDIENCE_UPDATE` 更新人群 Set，并切换日期 Hash 中的人群批次。
- `FULL_UPDATE` 用于 Banner 基础信息和人群包同时变化，完整更新上述两部分。
- 人群包使用 `SADD`，重复 userId 自动去重；重试会继续补齐中断前未写完的桶。
- Banner 日期 Hash 按 `bannerId` 执行 `HSET`。

Consumer 的固定执行顺序为：

```text
查询 MySQL 最新数据
→ 根据 eventType 更新 Banner 日期 Hash 和/或人群包
→ 回读 Redis 并覆盖 LocalCache
→ acknowledge Kafka offset
```

业务 Redis 或本地缓存刷新失败时，`acknowledge()` 不会执行。Consumer 配置为
`manual_immediate`，`DefaultErrorHandler` 使用固定间隔无限重投；同一 `bannerId` 作为 Kafka
message key，保证同分区内消息顺序。

## Guava LocalCache

- Key：`BannerDateCacheKey(productId, date)`，内嵌于 `BannerCacheService`。
- Value：不可变 `List<BannerRuntimeDTO>`，不缓存人群包。
- 默认 `expireAfterWrite` 30 秒，`maximumSize` 10000。
- 并发未命中时 Guava `Cache.get` 合并回源 Redis。
- Kafka/定时刷新完成后立即回填受影响的 Key。

多个 Banner 同时命中时按 `bannerId` 升序，取第一个。

## Banner 投放查询接口

```http
GET /api/v1/banners/delivery?productId=10&userId=1001
```

`productId` 必须为正数，`userId` 不能为空且最长 128 个字符。核心查询过程：

```text
当天 productId + date LocalCache
→ 未命中时读取 Redis 日期 Hash 并写入 LocalCache
→ 筛选 status=1 和 beginTime <= 当前时间 <= endTime
→ 根据 bucketCount 计算 bucketIndex
→ Redis SISMEMBER 检查 userId
```

以上逻辑在 `BannerDeliveryService` 中完成；当天未命中时再尝试历史日期兜底。

昨日兜底使用“前一天相同的时分秒”检查投放时间，否则昨日 Runtime 的 `endTime` 与今天时间
比较时必然已经过期。返回示例：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "bannerId": 20,
    "productId": 10,
    "url": "https://cdn.example.com/banner.png",
    "beginTime": 1784512800000,
    "endTime": 1784563199000,
    "source": "TODAY",
    "cacheDate": 1784476800000
  }
}
```

`source` 可能为 `TODAY`、`PREVIOUS_DATE` 或 `STATIC_DEFAULT`。静态默认 Banner 通过以下
环境变量配置：

```properties
BANNER_DEFAULT_ID=0
BANNER_DEFAULT_URL=https://cdn.example.com/default-banner.png
```

## 定时修复与消息对账

`BannerReconciliationService` 默认每 5 分钟触发，检查上一个完整窗口。例如任务在
`10:05～10:10` 之间触发时，只处理 `[10:00:00, 10:05:00)`，避免把仍在写入的数据纳入
对账。`update_time` 使用 Unix 毫秒，MySQL 直接进行 `BIGINT` 范围查询。
建表脚本已为该范围查询增加 `idx_update_time`。

旧数据库的时间列如果还是 `VARCHAR(19)`，必须先备份，再执行迁移脚本：

```bash
mysql < src/main/resources/db/migrate_time_to_bigint.sql
```

本次改造同时改变了 Redis 日期 Key 和缓存 JSON 的时间类型。部署时需要清理旧的
`product:*:date:*` 和 `banner:audience:*`，
再通过 Kafka 或定时对账从 MySQL 重建缓存；切换前也应处理完旧格式 Kafka 消息。

处理顺序如下：

1. 查询窗口内 MySQL 更新过的 Banner。
2. 检查 Banner 应覆盖的每一个 Redis 日期 Hash；任一字段不存在、JSON 无法解析、
   `updateTime` 为空或早于 MySQL，都判定需要修复。
3. 以稳定的 `schedule_{window}_{bannerId}` 作为 `audienceBatch`，复用完整刷新流程写人群包、
   切换 Banner JSON，并回填本实例 Guava LocalCache。
日志同时输出数量和排序后的 ID 明细。单个 Banner 修复异常不会阻止其他 Banner，异常 ID
额外记录在 `repairFailedIds` 中。默认配置为：

```properties
BANNER_RECONCILIATION_ENABLED=true
BANNER_RECONCILIATION_WINDOW_MINUTES=5
BANNER_RECONCILIATION_CRON=0 */5 * * * *
```

修改窗口大小时应同步调整 Cron 表达式。多实例部署会重复执行该任务，但稳定批次号、`SADD`
和 `HSET` 保证结果幂等；如需避免重复扫描，可在部署层只启用一个调度实例。

## 本地启动

1. 在 MySQL 中创建数据库，例如 `banner_deliver`。
2. 执行 [schema.sql](src/main/resources/db/schema.sql)。
3. 复制环境变量模板并填写本地连接信息：

```bash
cp .env.example .env
```

`.env` 会在启动时自动加载，已被 `.gitignore` 排除，不会提交到 GitHub。请不要在
`.env.example` 中填写真实密码。配置完成后启动：

```bash
mvn spring-boot:run
```

应用默认监听 `8080` 端口。项目默认不会自动执行建表脚本，避免误改已有数据库结构。

## 环境变量

完整参数及安全默认值见 [.env.example](.env.example)，包含：

- MySQL 连接、超时和 HikariCP 连接池参数
- Redis 连接、超时和连接池参数
- Kafka Broker、Consumer Group、并发度和事件 Topic
- 人群包目标单桶人数、LocalCache 和 Redis TTL 参数
- 5 分钟对账任务及日志级别
