CREATE TABLE IF NOT EXISTS banner_info (
    banner_id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Banner主键ID',
    product_id BIGINT NOT NULL COMMENT '商品ID',
    begin_time BIGINT NOT NULL COMMENT '开始时间，Unix毫秒',
    end_time BIGINT NOT NULL COMMENT '结束时间，Unix毫秒',
    url VARCHAR(1000) NOT NULL COMMENT 'Banner资源地址',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态：0-停用，1-启用',
    create_time BIGINT NOT NULL COMMENT '创建时间，Unix毫秒',
    update_time BIGINT NOT NULL COMMENT '更新时间，Unix毫秒',
    PRIMARY KEY (banner_id),
    INDEX idx_product_id (product_id),
    INDEX idx_update_time (update_time),
    INDEX idx_product_time (product_id, begin_time, end_time)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci
  COMMENT='Banner基础配置表';

CREATE TABLE IF NOT EXISTS banner_crowd (
    banner_crowd_id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Banner人群包主键ID',
    banner_id BIGINT NOT NULL COMMENT 'Banner ID',
    page_num INT NOT NULL COMMENT '人群包分页编号或桶编号',
    user_list BLOB NOT NULL COMMENT '用户ID列表二进制数据',
    create_time BIGINT NOT NULL COMMENT '创建时间，Unix毫秒',
    update_time BIGINT NOT NULL COMMENT '更新时间，Unix毫秒',
    PRIMARY KEY (banner_crowd_id),
    UNIQUE KEY uk_banner_page (banner_id, page_num),
    INDEX idx_banner_id (banner_id)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci
  COMMENT='Banner人群包表';
