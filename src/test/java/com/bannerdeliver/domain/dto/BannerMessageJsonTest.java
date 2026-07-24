package com.bannerdeliver.domain.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BannerMessageJsonTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldSerializeKafkaEventWithSpecifiedFields() throws Exception {
        BannerDeliveryEvent event = BannerDeliveryEvent.builder()
                .eventId("evt_20260720_001")
                .eventType(BannerEventType.AUDIENCE_UPDATE)
                .bannerId(20L)
                .productId(10L)
                .oldProductId(10L)
                .changedFields(List.of("user_list"))
                .eventTime("2026-07-20 09:30:00")
                .build();

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(event));

        assertThat(json.get("eventId").asText()).isEqualTo("evt_20260720_001");
        assertThat(json.get("eventType").asText()).isEqualTo("AUDIENCE_UPDATE");
        assertThat(json.get("bannerId").asLong()).isEqualTo(20L);
        assertThat(json.get("changedFields").get(0).asText()).isEqualTo("user_list");
    }

    @Test
    void shouldSerializeRuntimeJsonWithAudienceVersion() throws Exception {
        BannerRuntimeDTO runtime = BannerRuntimeDTO.builder()
                .bannerId(20L)
                .productId(10L)
                .url("https://cdn.example.com/banner.png")
                .beginTime("2026-07-20 10:00:00")
                .endTime("2026-07-20 23:59:59")
                .status(1)
                .bucketCount(20)
                .audienceBatch("evt_20260720_001")
                .updateTime("2026-07-20 09:30:00")
                .build();

        BannerRuntimeDTO decoded = objectMapper.readValue(
                objectMapper.writeValueAsString(runtime), BannerRuntimeDTO.class);

        assertThat(decoded).usingRecursiveComparison().isEqualTo(runtime);
    }
}
