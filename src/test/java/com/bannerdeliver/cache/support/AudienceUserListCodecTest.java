package com.bannerdeliver.cache.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AudienceUserListCodecTest {

    private final AudienceUserListCodec codec = new AudienceUserListCodec(new ObjectMapper());

    @Test
    void shouldDecodeJsonArray() {
        assertThat(codec.decode("[\"1001\",1002,\"用户-1003\"]"))
                .containsExactly("1001", "1002", "用户-1003");
    }

    @Test
    void shouldDecodeCommaAndNewlineSeparatedUsers() {
        assertThat(codec.decode("1001, 1002\n用户-1003"))
                .containsExactly("1001", "1002", "用户-1003");
    }
}
