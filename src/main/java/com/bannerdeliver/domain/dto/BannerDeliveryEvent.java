package com.bannerdeliver.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BannerDeliveryEvent {

    @NotBlank
    @Pattern(regexp = "[A-Za-z0-9._-]+")
    private String eventId;

    @NotNull
    private BannerEventType eventType;

    @NotNull
    private Long bannerId;

    private Long productId;
    private Long oldProductId;

    @Builder.Default
    private List<String> changedFields = new ArrayList<>();

    @NotBlank
    private String eventTime;
}
