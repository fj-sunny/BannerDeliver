package com.bannerdeliver.controller;

import com.bannerdeliver.domain.dto.BannerDeliveryResult;
import com.bannerdeliver.domain.dto.BannerDeliverySource;
import com.bannerdeliver.domain.dto.BannerRuntimeDTO;
import com.bannerdeliver.exception.GlobalExceptionHandler;
import com.bannerdeliver.service.BannerDeliveryService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BannerDeliveryControllerTest {

    @Test
    void shouldReturnUnifiedBannerDeliveryResponse() throws Exception {
        BannerDeliveryService deliveryService = mock(BannerDeliveryService.class);
        BannerRuntimeDTO runtime = BannerRuntimeDTO.builder()
                .bannerId(20L)
                .productId(10L)
                .url("https://cdn.example.com/banner.png")
                .beginTime(1784512800000L)
                .endTime(1784563199000L)
                .build();
        when(deliveryService.query(10L, "1001")).thenReturn(new BannerDeliveryResult(
                runtime,
                BannerDeliverySource.TODAY,
                1784476800000L));
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new BannerDeliveryController(deliveryService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mockMvc.perform(get("/api/v1/banners/delivery")
                        .param("productId", "10")
                        .param("userId", "1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data.bannerId").value(20))
                .andExpect(jsonPath("$.data.productId").value(10))
                .andExpect(jsonPath("$.data.url")
                        .value("https://cdn.example.com/banner.png"))
                .andExpect(jsonPath("$.data.source").value("TODAY"))
                .andExpect(jsonPath("$.data.cacheDate").value(1784476800000L));
    }
}
