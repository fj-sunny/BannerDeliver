package com.bannerdeliver.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 将 MySQL BLOB 对应的 String 解码为用户 ID。
 *
 * <p>标准格式是 JSON 数组，同时兼容逗号或换行分隔的历史数据。</p>
 */
@Component
@RequiredArgsConstructor
public class AudienceUserListCodec {

    private final ObjectMapper objectMapper;

    /** 将 BLOB 对应的 String 解码为用户 ID 列表，支持 JSON 数组和逗号/换行分隔格式。 */
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

    /** 解析 JSON 数组格式的 user_list。 */
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

    /** 去除空白后将非空 userId 加入结果列表。 */
    private void addIfPresent(List<String> users, String userId) {
        if (userId != null && !userId.isBlank()) {
            users.add(userId.trim());
        }
    }
}
