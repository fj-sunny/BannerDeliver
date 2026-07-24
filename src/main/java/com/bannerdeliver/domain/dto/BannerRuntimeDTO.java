package com.bannerdeliver.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
