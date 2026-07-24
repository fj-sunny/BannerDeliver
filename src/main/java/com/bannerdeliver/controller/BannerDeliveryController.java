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

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/banners")
public class BannerDeliveryController {

    private final BannerDeliveryService deliveryService;

    @GetMapping("/delivery")
    public Result<BannerDeliveryVO> queryBanner(
            @RequestParam @Positive Long productId,
            @RequestParam @NotBlank @Size(max = 128) String userId) {
        return Result.success(BannerDeliveryVO.from(
                deliveryService.query(productId, userId)));
    }
}
