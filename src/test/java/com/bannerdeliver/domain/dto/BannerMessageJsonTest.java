package com.bannerdeliver.domain.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BannerMessageJsonTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldSerializeKafkaEventWithSpecifiedFields() throws Exception {
        BannerDeliveryEvent event = BannerDeliveryEvent.builder()
                .eventId("evt_20260720_001")
                .bannerId(20L)
                .eventType(BannerEventType.AUDIENCE_UPDATE)
                .eventTime(1784511000000L)
                .build();

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(event));

        assertThat(json.get("eventId").asText()).isEqualTo("evt_20260720_001");
        assertThat(json.get("bannerId").asLong()).isEqualTo(20L);
        assertThat(json.get("eventType").asText()).isEqualTo("AUDIENCE_UPDATE");
        assertThat(json.get("eventTime").asLong()).isEqualTo(1784511000000L);
        assertThat(json.size()).isEqualTo(4);
    }

    @Test
    void shouldSerializeRuntimeJsonWithAudienceVersion() throws Exception {
        BannerRuntimeDTO runtime = BannerRuntimeDTO.builder()
                .bannerId(20L)
                .productId(10L)
                .url("https://cdn.example.com/banner.png")
                .beginTime(1784512800000L)
                .endTime(1784563199000L)
                .status(1)
                .bucketCount(20)
                .audienceBatch("evt_20260720_001")
                .updateTime(1784511000000L)
                .build();

        BannerRuntimeDTO decoded = objectMapper.readValue(
                objectMapper.writeValueAsString(runtime), BannerRuntimeDTO.class);

        assertThat(decoded).usingRecursiveComparison().isEqualTo(runtime);
    }
}
