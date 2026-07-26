package com.bannerdeliver.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 写入 Redis 日期 Hash 的 Banner 运行时快照。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BannerRuntimeDTO {

    private Long bannerId;
    private Long productId;
    private String url;
    private String beginTime;
    private String endTime;
    private Integer status;
    private Integer bucketCount;
    private String audienceBatch;
    private String updateTime;
}
