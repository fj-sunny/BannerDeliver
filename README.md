# BannerDeliver

Banner 投放系统后端骨架，基于 Java 17、Spring Boot、MyBatis-Plus 和 MySQL。

## 当前结构

```text
src/main/java/com/bannerdeliver
├── BannerDeliverApplication.java
├── entity
│   ├── BannerInfo.java
│   └── BannerCrowd.java
├── infrastructure/mybatis
│   └── StringBlobTypeHandler.java
├── mapper
└── service
```

当前阶段包含两张表的实体映射、Mapper、Service、数据源配置和建表脚本。Kafka、Redis、
LocalCache、定时补偿和对外接口将在对应业务模块中继续实现。

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
