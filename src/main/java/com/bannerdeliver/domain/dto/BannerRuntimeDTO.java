package com.bannerdeliver.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 写入 Redis 日期 Hash 的 Banner 运行时快照，也是 L1 缓存 Value 的元素类型。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BannerRuntimeDTO {

    /** Banner 主键，同时作为 Hash field 名。 */
    private Long bannerId;
    /** 所属商品 ID。 */
    private Long productId;
    /** 图片/跳转 URL。 */
    private String url;
    /** 投放开始时间，格式 yyyy-MM-dd HH:mm:ss。 */
    private String beginTime;
    /** 投放结束时间，格式 yyyy-MM-dd HH:mm:ss。 */
    private String endTime;
    /** 状态：0-停用，1-启用。 */
    private Integer status;
    /** 人群包分桶总数；0 表示无人群限制。 */
    private Integer bucketCount;
    /** 人群包批次号，对应 Redis Key 中的 audienceBatch，非桶编号。 */
    private String audienceBatch;
    /** 与 MySQL update_time 对齐，用于防旧覆盖新。 */
    private String updateTime;
}
