package com.bannerdeliver.common;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** API 响应状态码枚举。 */
@Getter
@RequiredArgsConstructor
public enum ResultCode {

    SUCCESS(0, "success"),
    PARAM_ERROR(400, "参数错误"),
    AUTH_ERROR(401, "认证失败"),
    PERMISSION_DENIED(403, "权限不足"),
    BUSINESS_ERROR(1000, "业务处理失败"),
    INTERNAL_SERVER_ERROR(500, "系统内部错误");

    private final int code;
    private final String message;
}
