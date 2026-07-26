# BannerDeliver

Banner 投放系统后端骨架，基于 Java 17、Spring Boot、MyBatis-Plus 和 MySQL。

## 核心流程

项目只保留四条主链路，代码集中在少数几个 Service 中：

```text
1. 用户查询   Controller → BannerDeliveryService → BannerCacheService（L1 Guava + L2 Redis）
2. 数据变更   BannerInfo/CrowdService → MySQL 事务 → Kafka Producer
3. 缓存刷新   Kafka Consumer → BannerCacheService.refreshFromMysql
4. 定时对账   BannerReconciliationService → BannerCacheService.repairFromMysql
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
消费端由 `BannerEventConsumer` 调用 `BannerCacheService` 从 MySQL 重读并刷新 Redis。

## 字段映射

| MySQL 字段 | Java 类型 | 说明 |
| --- | --- | --- |
| `BIGINT` | `Long` | 主键及关联 ID |
| `INT` | `Integer` | 人群包页码/桶号 |
| `TINYINT` | `Integer` | Banner 状态 |
| `VARCHAR(19)` | `String` | 所有时间字段，不转为日期类型 |
| `BLOB` | `String` | `user_list` 通过 UTF-8 TypeHandler 读写 |

`StringBlobTypeHandler` 负责把 Java `String` 按 UTF-8 转成字节写入 BLOB，并在查询时按
UTF-8 还原，避免依赖 JDBC 驱动的隐式类型转换。

`user_list` 推荐保存 JSON 数组，例如 `["1001","1002"]`。当前解码器也兼容逗号或
换行分隔的历史格式。

## 多级缓存

| 层级 | 实现 | 说明 |
| --- | --- | --- |
| L1 | Guava `Cache`（内嵌于 `BannerCacheService`） | Key=`productId+date`，TTL 30s |
| L2 | Redis Hash | `product:{productId}:date:{yyyyMMdd}` 存 Banner JSON |
| 人群 | Redis Set | 分桶 `SISMEMBER` 判断用户归属 |

查询：`BannerCacheService.getBanners` → 过滤 status/时间 → `isAudienceMember`。
刷新后主动失效 L1；跨实例最终一致依赖 L1 TTL。

## Redis 缓存

| 用途 | Key | 类型 |
| --- | --- | --- |
| Banner 日期缓存 | `product:{productId}:date:{yyyyMMdd}` | Hash |
| 人群包分桶 | `banner:audience:{bannerId}:{audienceBatch}:bucket:{index}` | Set |
| Banner 日期索引 | `banner:date-keys:{bannerId}` | Set |
| Banner 最新版本 | `banner:version:{bannerId}` | String |
| MQ 消费窗口 | `mq:consume:{yyyyMMddHHmm}` | Set |

日期索引和版本 Key 是内部维护 Key，用于删除、商品变更、日期变更清理和阻止旧数据覆盖。
业务查询仍只读取设计中约定的日期 Hash 与人群包 Set。

- 日期 Hash 在业务日期加两天的 `00:00` 绝对过期。
- 人群包按整个 Banner 日期范围的最晚过期时间设置 TTL，并加入 Key 派生的稳定抖动。
- `bucketCount = ceil(userCount / targetBucketSize)`；空人群包为 `0`。
- `bucketIndex = floorMod(userId.hashCode(), bucketCount)`。
- 新 `audienceBatch` 全部写完后才切换日期 Hash；旧批次等待 TTL 自动删除。
- Redis 读写统一在 `BannerCacheService` 中完成。

## Kafka 刷新

- Topic：`banner-delivery-event`，可通过 `BANNER_EVENT_TOPIC` 修改。
- Producer 始终以 `bannerId` 字符串作为 message key。
- Consumer 使用手动提交 offset，Redis 和消费窗口记录全部成功后才确认消息。
- 处理失败时不提交 offset，并按固定间隔重新投递。
- Consumer 只使用消息定位 Banner，业务字段从 MySQL 的 `banner_info`、`banner_crowd`
  重新读取。
- 重复事件通过 Redis `updateTime` 版本 Key 幂等跳过。

Kafka Topic 需要在部署环境预先创建。更新 MySQL 成功后，业务服务调用
`BannerEventProducer.sendAfterCommit(event)` 发送事件。`BannerInfoService`
和 `BannerCrowdService` 已接入该生产者。

### 重复消费与幂等

系统不额外保存“已消费 eventId”来阻止重复消息，而是让每一步都可以安全重试：

- 相同 Kafka 消息始终使用同一个 `eventId` 作为 `audienceBatch`。
- 人群包使用 `SADD`，重复 userId 自动去重；重试会继续补齐中断前未写完的桶。
- Banner 日期 Hash 按 `bannerId` 执行 `HSET`；删除使用可重复执行的 `HDEL`。
- 写入前先比较 JSON 中的 `updateTime` 和 `banner:version:{bannerId}`，旧版本不能覆盖新版本。
- 版本比较在 Java 层完成，不使用 Lua。

Consumer 的固定执行顺序为：

```text
查询 MySQL 最新快照
→ 完整写入版本化人群包
→ 切换 Banner 日期 Hash
→ 清理 LocalCache
→ SADD mq:consume:{window} bannerId
→ acknowledge Kafka offset
```

业务 Redis 刷新或消费窗口记录任一步失败，`acknowledge()` 都不会执行。Consumer 配置为
`manual_immediate`，`DefaultErrorHandler` 使用固定间隔无限重投；同一 `bannerId` 作为 Kafka
message key，保证同分区内消息顺序。

## Guava LocalCache

- Key：`BannerDateCacheKey(productId, date)`，内嵌于 `BannerCacheService`。
- Value：不可变 `List<BannerRuntimeDTO>`，不缓存人群包。
- 默认 `expireAfterWrite` 30 秒，`maximumSize` 10000。
- 并发未命中时 Guava `Cache.get` 合并回源 Redis。
- Kafka/定时刷新完成后主动失效受影响的 Key。

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
    "beginTime": "2026-07-20 10:00:00",
    "endTime": "2026-07-20 23:59:59",
    "source": "TODAY",
    "cacheDate": "2026-07-20"
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
对账。由于 `update_time` 固定使用 `yyyy-MM-dd HH:mm:ss`，MySQL 查询可直接使用字符串范围。
建表脚本已为该范围查询增加 `idx_update_time`。已有数据库需要执行一次：

```sql
ALTER TABLE banner_info ADD INDEX idx_update_time (update_time);
```

处理顺序如下：

1. 查询窗口内 MySQL 更新过的 Banner。
2. 检查 Banner 应覆盖的每一个 Redis 日期 Hash；任一字段不存在、JSON 无法解析、
   `updateTime` 为空或早于 MySQL，都判定需要修复。
3. 以稳定的 `schedule_{window}_{bannerId}` 作为 `audienceBatch`，复用完整刷新流程写人群包、
   切换 Banner JSON，并失效本实例 Guava LocalCache。
4. 读取 `mq:consume:{window}`，在本次任务内计算对账集合并输出 `[BannerAudit]` 日志。

核心集合关系：

```text
suspectedLostIds = scheduleRepairIds - mqConsumedIds
consumeButStaleIds = scheduleRepairIds ∩ mqConsumedIds
```

日志同时输出数量和排序后的 ID 明细。单个 Banner 修复异常不会阻止其他 Banner，异常 ID
额外记录在 `repairFailedIds` 中。默认配置为：

```properties
BANNER_RECONCILIATION_ENABLED=true
BANNER_RECONCILIATION_WINDOW_MINUTES=5
BANNER_RECONCILIATION_CRON=0 */5 * * * *
```

修改窗口大小时应同步调整 Cron 表达式。多实例部署会重复执行该任务，但稳定批次号、`SADD`、
`HSET` 和固定 `EXPIREAT` 保证结果幂等；如需避免重复扫描，可在部署层只启用一个调度实例。

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
