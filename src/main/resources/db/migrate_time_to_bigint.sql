-- 仅用于旧表从 yyyy-MM-dd HH:mm:ss 字符串迁移到 Unix 毫秒。
-- 执行前请备份数据库，并确认旧时间均按 Asia/Shanghai 写入。
SET @previous_time_zone = @@session.time_zone;
SET SESSION time_zone = '+08:00';

ALTER TABLE banner_info
    ADD COLUMN begin_time_ms BIGINT NULL,
    ADD COLUMN end_time_ms BIGINT NULL,
    ADD COLUMN create_time_ms BIGINT NULL,
    ADD COLUMN update_time_ms BIGINT NULL;

UPDATE banner_info
SET begin_time_ms = UNIX_TIMESTAMP(STR_TO_DATE(begin_time, '%Y-%m-%d %H:%i:%s')) * 1000,
    end_time_ms = UNIX_TIMESTAMP(STR_TO_DATE(end_time, '%Y-%m-%d %H:%i:%s')) * 1000,
    create_time_ms = UNIX_TIMESTAMP(STR_TO_DATE(create_time, '%Y-%m-%d %H:%i:%s')) * 1000,
    update_time_ms = UNIX_TIMESTAMP(STR_TO_DATE(update_time, '%Y-%m-%d %H:%i:%s')) * 1000;

ALTER TABLE banner_info
    DROP INDEX idx_update_time,
    DROP INDEX idx_product_time,
    DROP COLUMN begin_time,
    DROP COLUMN end_time,
    DROP COLUMN create_time,
    DROP COLUMN update_time,
    CHANGE COLUMN begin_time_ms begin_time BIGINT NOT NULL COMMENT '开始时间，Unix毫秒',
    CHANGE COLUMN end_time_ms end_time BIGINT NOT NULL COMMENT '结束时间，Unix毫秒',
    CHANGE COLUMN create_time_ms create_time BIGINT NOT NULL COMMENT '创建时间，Unix毫秒',
    CHANGE COLUMN update_time_ms update_time BIGINT NOT NULL COMMENT '更新时间，Unix毫秒',
    ADD INDEX idx_update_time (update_time),
    ADD INDEX idx_product_time (product_id, begin_time, end_time);

ALTER TABLE banner_crowd
    ADD COLUMN create_time_ms BIGINT NULL,
    ADD COLUMN update_time_ms BIGINT NULL;

UPDATE banner_crowd
SET create_time_ms = UNIX_TIMESTAMP(STR_TO_DATE(create_time, '%Y-%m-%d %H:%i:%s')) * 1000,
    update_time_ms = UNIX_TIMESTAMP(STR_TO_DATE(update_time, '%Y-%m-%d %H:%i:%s')) * 1000;

ALTER TABLE banner_crowd
    DROP COLUMN create_time,
    DROP COLUMN update_time,
    CHANGE COLUMN create_time_ms create_time BIGINT NOT NULL COMMENT '创建时间，Unix毫秒',
    CHANGE COLUMN update_time_ms update_time BIGINT NOT NULL COMMENT '更新时间，Unix毫秒';

SET SESSION time_zone = @previous_time_zone;
