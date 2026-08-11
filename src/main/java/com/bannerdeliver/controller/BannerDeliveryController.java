package com.bannerdeliver.controller;

import com.bannerdeliver.common.Result;
import com.bannerdeliver.domain.vo.BannerDeliveryVO;
import com.bannerdeliver.service.BannerDeliveryService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Banner 投放查询的 HTTP 入口。
 *
 * <p>控制器只负责参数校验、调用应用服务并包装统一响应；缓存回源、投放筛选等
 * 业务规则均位于 Service 层，避免 Web 层直接依赖 Redis 或数据库。</p>
 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/banners")
public class BannerDeliveryController {

    private final BannerDeliveryService deliveryService;

    /**
     * 按商品和用户查询当前应展示的 Banner。
     * {@code productId} 用于定位商品配置，{@code userId} 用于判断人群包归属。
     */
    @GetMapping("/delivery")
    public Result<BannerDeliveryVO> queryBanner(
            @RequestParam @Positive Long productId,
            @RequestParam @NotBlank @Size(max = 128) String userId) {
        return Result.success(BannerDeliveryVO.from(
                deliveryService.query(productId, userId)));
    }
}
