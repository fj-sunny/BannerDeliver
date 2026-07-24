package com.bannerdeliver.cache.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * user_list 推荐存 JSON 数组；同时兼容逗号和换行分隔的历史数据。
 */
/**
 * 将 MySQL BLOB 对应的 String 解码为用户 ID。
 *
 * <p>标准格式是 JSON 数组，同时兼容逗号或换行分隔的历史数据。</p>
 */
@Component
@RequiredArgsConstructor
public class AudienceUserListCodec {

    private final ObjectMapper objectMapper;

    public List<String> decode(String encodedUsers) {
        if (encodedUsers == null || encodedUsers.isBlank()) {
            return List.of();
        }
        String value = encodedUsers.trim();
        if (value.startsWith("[")) {
            return decodeJsonArray(value);
        }
        List<String> users = new ArrayList<>();
        for (String userId : value.split("[,\\r\\n]+")) {
            addIfPresent(users, userId);
        }
        return users;
    }

    private List<String> decodeJsonArray(String value) {
        try {
            JsonNode root = objectMapper.readTree(value);
            if (!root.isArray()) {
                throw new IllegalArgumentException("user_list JSON must be an array");
            }
            List<String> users = new ArrayList<>(root.size());
            root.forEach(node -> addIfPresent(users, node.asText()));
            return users;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid user_list JSON", exception);
        }
    }

    private void addIfPresent(List<String> users, String userId) {
        if (userId != null && !userId.isBlank()) {
            users.add(userId.trim());
        }
    }
}
