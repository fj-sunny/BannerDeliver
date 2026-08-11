# BannerDeliver

## 1. 项目背景

BannerDeliver 是商品 Banner 投放的读路径与缓存同步服务。

运营端负责维护 MySQL 中的 Banner 配置（`banner_info`）和人群包（`banner_crowd`），变更后向 Kafka 投递刷新事件。本服务**不写 MySQL、不生产业务刷新消息**，职责是：

1. 消费运营端 Kafka 事件，从 MySQL 重读最新数据，刷新 Redis 与本机 LocalCache
2. 对外提供用户投放查询接口，按状态、投放时间、人群包匹配 Banner
3. 定时对账：发现 Redis 落后于 MySQL 时自动补偿修复

技术栈：Java 17、Spring Boot 3、MyBatis-Plus、MySQL、Redis、Kafka、Guava Cache。

```text
运营端                 BannerDeliver                      用户
  │                        │                              │
  │ 写 MySQL               │                              │
  │ 发 Kafka ───────────► │ Consumer 刷新 Redis/L1        │
  │                        │                              │
  │                        │ ◄── GET /banners/delivery ── │
  │                        │ 读 L1/Redis + 匹配人群        │
  │                        │ ──► 返回 Banner URL          │
```

---

## 2. 从运营端 Kafka 消息看架构与流程

### 2.1 架构

```text
运营端改 MySQL
      │
      ▼
Kafka Topic: banner-delivery-event
  key   = bannerId（保证同 Banner 分区有序）
  value = BannerDeliveryEvent JSON
      │
      ▼
BannerEventConsumer（手动 ack）
      │
      ▼
BannerCacheService.refreshBannerCache
  ├─ 只读 MySQL：banner_info / banner_crowd
  ├─ 按需写入 Redis 人群 Set（新 audienceBatch）
  ├─ HSET 日期 Hash 中的 BannerRuntimeDTO
  └─ invalidate 本机 Guava LocalCache
      │
      ▼
acknowledge offset
```

辅助链路：`BannerReconciliationService` 默认每 5 分钟扫描上一个完整时间窗口内 `update_time` 有变更的 Banner；若 Redis Runtime 缺失或落后于 MySQL，则以 `FULL_UPDATE` 走同一套刷新逻辑补偿。

模块边界：

| 模块 | 职责 |
| --- | --- |
| `BannerEventConsumer` | 解析/校验消息，调用刷新，成功后 ack |
| `BannerCacheService` | 多级缓存读写、人群分桶写入、刷新编排 |
| `BannerInfoService` / `BannerCrowdService` | MySQL **只读** |
| `BannerReconciliationService` | 定时对账与补偿 |

### 2.2 消息体

Topic 默认：`banner-delivery-event`（可用 `BANNER_EVENT_TOPIC` 覆盖）。

```json
{
  "eventId": "evt_20260811_001",
  "bannerId": 20,
  "eventType": "AUDIENCE_UPDATE",
  "eventTime": 1723370000000
}
```

| 字段 | 含义 |
| --- | --- |
| `eventId` | 事件唯一 ID；人群相关刷新时同时作为 Redis 新 `audienceBatch`（不落 MySQL） |
| `bannerId` | 目标 Banner；建议同时作为 Kafka message key |
| `eventType` | 刷新范围，见下表 |
| `eventTime` | 可选；事件发生时间（Unix 毫秒），供排查/审计。对账与 Redis 版本以 MySQL `update_time` 为准 |

`eventType`：

| 类型 | 含义 | 本服务行为 |
| --- | --- | --- |
| `BANNER_UPDATE` | 基础信息变化（URL/状态/投放期等） | 尽量复用已有人群 batch，只更新日期 Hash；**仅当投放期变化使人群 TTL 基准变大**（如 `end_time` 跨天延长）时才续期旧人群 Key TTL |
| `AUDIENCE_UPDATE` | 人群包变化 | 用 `eventId` 写新人群 batch，再切换 Runtime 中的 `audienceBatch`/`bucketCount` |
| `FULL_UPDATE` | 基础信息 + 人群同时变化 | 完整重写人群与日期 Hash（对账补偿也用此类型） |

约定：

- 消息**只携带定位信息**，业务字段一律从 MySQL 重读，避免消息与库不一致
- 必填：`eventId`、`bannerId`、`eventType`；`eventTime` 可选（排查用，不参与对账）
- `eventId` 建议仅含 `[A-Za-z0-9._-]`，否则 Consumer 直接拒收
- 失败不 ack，固定间隔无限重投；同 `bannerId` 同分区有序

### 2.3 Redis Key 设计

#### 日期 Hash（Banner 运行时快照）

```text
Key:    product:{productId}:date:{业务日零点毫秒}
Type:   Hash
Field:  {bannerId}
Value:  BannerRuntimeDTO JSON
```

`BannerRuntimeDTO` 主要字段：

| 字段 | 说明 |
| --- | --- |
| `bannerId` / `productId` / `url` | Banner 标识与资源 |
| `beginTime` / `endTime` | 投放期，Unix 毫秒 |
| `status` | `0` 停用，`1` 启用 |
| `bucketCount` | 人群分桶数；`0` 表示无有效人群 |
| `audienceBatch` | 当前生效的人群批次号（仅存在于 Redis，不是 MySQL 列） |
| `updateTime` | 对齐 MySQL `update_time`，供对账比较 |

业务日按固定时区偏移计算（默认 UTC+8，`BANNER_CACHE_ZONE_OFFSET_MILLIS=28800000`）。  
日期 Hash TTL：业务日 + N 天（默认 N=2）。

#### 人群包分桶 Set

```text
Key:  banner:audience:{bannerId}:{audienceBatch}:bucket:{index}
Type: Set
成员: userId
```

| 规则 | 计算方式 |
| --- | --- |
| `bucketCount` | `ceil(userCount / targetBucketSize)`，默认单桶目标 50000；空人群为 `0` |
| `bucketIndex` | `floorMod(userId.hashCode(), bucketCount)` |
| 写入并行度 | `banner.audience.write-parallelism`（默认 4）：先单线程分桶，再并行 `SADD`/`EXPIRE` |
| 人群 TTL 基准 | 该 Banner 投放期内所有日期 Hash 过期点的**最大值** |
| 抖动 | 按 Key 做 CRC32 稳定抖动（默认 0～300 秒）；同一 Key 重试抖动不变 |
| `BANNER_UPDATE` 续期 | 仅当新 TTL 基准 **大于** 旧 Runtime 投放期算出的基准时，才续期旧 `audienceBatch` 各桶 |

本机 L1（Guava）：

```text
Key:   BannerDateCacheKey(productId, date)
Value: List<BannerRuntimeDTO>（不可变列表，不含人群包本身）
TTL:   默认 expireAfterWrite 30s，maximumSize 10000
```

刷新成功后对本机受影响 Key 执行 `invalidate`，下次查询再回源 Redis。

### 2.4 完整刷新流程（以人群变更为例）

```text
运营端更新 banner_crowd / banner_info.update_time
        ↓
运营端投递 Kafka：AUDIENCE_UPDATE，eventId = evt_002
        ↓
Consumer 校验消息 → refreshBannerCache(bannerId, evt_002, AUDIENCE_UPDATE)
        ↓
只读 MySQL 最新 banner_info + banner_crowd
        ↓
计算投放期内全部业务日 → 得到日期 Hash Key 列表
        ↓
audienceBatch = evt_002
计算 bucketCount，按桶 SADD 写入
  banner:audience:{bannerId}:evt_002:bucket:*
（不删除旧 batch evt_001）
        ↓
全部新 bucket 写成功后，HSET 各日期 Hash：
  BannerRuntimeDTO.audienceBatch = evt_002
  BannerRuntimeDTO.bucketCount   = 新值
（读路径通过指针切换看到新人群；旧 Set 靠 TTL 自然过期）
        ↓
invalidate 本机受影响 LocalCache
        ↓
acknowledge Kafka offset
```

要点：

1. **先写全量新人群，再切换 Runtime 指针**，避免读到「半成品」batch  
2. **旧人群 Key 不主动 DEL**，靠 TTL 回收  
3. **重复消费可安全重试**：`SADD`/`HSET` 幂等；同 `eventId` 作为 batch 可继续补齐中断的桶  
4. `BANNER_UPDATE` 若 Redis 已有可复用的 `audienceBatch`/`bucketCount`，则不重写人群；只有人群 TTL 基准因投放期延长而变大时，才续期旧人群 Key
5. 人群写入：先单线程分桶，再按 `write-parallelism` 并行刷 Redis；全部成功后才切换 Runtime 指针

---

## 3. 从用户角度看获取 Banner 的业务逻辑

### 3.1 接口

```http
GET /api/v1/banners/delivery?productId=10&userId=1001
```

- `productId`：正数  
- `userId`：非空，最长 128 字符  

返回示例：

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

`source`：

| 值 | 含义 |
| --- | --- |
| `TODAY` | 当天业务日缓存命中 |
| `PREVIOUS_DATE` | 历史业务日缓存兜底命中 |
| `STATIC_DEFAULT` | 配置的静态默认 Banner |

### 3.2 匹配逻辑（`BannerDeliveryService`）

```text
now = 当前时刻
today = 业务日零点(now)

① 读当天缓存候选列表（L1 → 未命中再回源 Redis 日期 Hash）
   按 bannerId 升序逐个判断：
     1. status == 1
     2. beginTime <= now <= endTime
     3. userId 属于该 Banner 人群包
        （bucketIndex = hash % bucketCount，再 SISMEMBER）
   命中则返回，source = TODAY

② 当天未命中 → 读历史业务日缓存（默认回退 1 天）
   仍按上面 1 → 2 → 3 判断（时间条件继续比「现在」）
   命中则返回，source = PREVIOUS_DATE

③ 仍未命中 → 返回静态默认 Banner
   source = STATIC_DEFAULT
```

人群判断细节：

```text
若 bucketCount <= 0 → 非人群成员
否则：
  bucketIndex = floorMod(userId.hashCode(), bucketCount)
  SISMEMBER banner:audience:{bannerId}:{audienceBatch}:bucket:{bucketIndex} userId
```

注意：status / 投放时间未通过时，**不会**发起人群包查询。

---

## 4. 难点与解决方法

### 4.1 大人群包不能阻塞读路径，也不能半切换

**难点**：人群可达百万级，全量写 Redis 耗时长；若边写边切指针，用户会读到不完整人群。

**做法**：

- 人群按桶拆分写入（默认约 5 万用户/桶，批量 SADD）
- 新 `audienceBatch`（`eventId`）全部写完后，才 HSET 切换 Runtime 指针
- 旧 batch 不删，靠 TTL 回收，切换瞬间读路径原子切到新 batch

### 4.2 消息与库一致性、重复消费

**难点**：Kafka 可能重复投递；消息若携带业务快照，容易与 MySQL 最终态不一致。

**做法**：

- 消息必带 `eventId/bannerId/eventType`（`eventTime` 可选），业务一律重读 MySQL
- 人群 batch 用 `eventId` 命名，重试可继续 `SADD` 补齐
- 手动 ack：Redis/L1 全部成功后才提交 offset；失败固定间隔重投
- message key = `bannerId`，保证同 Banner 有序处理

### 4.3 多级缓存一致性

**难点**：Guava L1 与 Redis L2 并存；本机刷新后其他实例仍可能短暂读到旧 L1。

**做法**：

- 刷新成功后对本机受影响 Key 直接 `invalidate`（不回填覆盖）
- L1 短 TTL（默认 30s），跨实例最终一致依赖 TTL
- 定时对账扫描 MySQL `update_time` 窗口，落后则 `FULL_UPDATE` 补偿

### 4.4 投放查询要又快又准

**难点**：高 QPS 下既要过滤状态/时间，又要做人群归属判断。

**做法**：

- 候选 Banner 放日期 Hash，L1 按 `productId+date` 缓存列表
- 严格按 `status → 当前时间是否在投放期 → 人群 SISMEMBER` 短路过滤
- 多个命中取最小 `bannerId`；当天无结果再走历史日缓存与静态默认兜底

### 4.5 职责边界清晰

**难点**：若投放服务同时写库、发消息，会与运营端职责纠缠，难演进。

**做法**：

- MySQL 增删改与 Kafka 生产留给运营端
- 本服务只做：消费事件 → 只读 MySQL → 刷新缓存 → 用户查询 / 对账补偿

---

## 附录：本地启动与配置

1. 创建 MySQL 库并执行 [schema.sql](src/main/resources/db/schema.sql)  
2. 复制 `.env.example` 为 `.env` 并填写连接信息  
3. 预先创建 Kafka Topic（默认 `banner-delivery-event`）  
4. 启动：

```bash
./mvnw spring-boot:run
```

默认端口 `8080`。完整环境变量见 [.env.example](.env.example)。

若历史库时间列仍是字符串，需先备份再执行：

```bash
mysql < src/main/resources/db/migrate_time_to_bigint.sql
```

时间类型切换后，建议清理旧 Redis Key（`product:*:date:*`、`banner:audience:*`），再通过 Kafka 或对账任务重建缓存。
