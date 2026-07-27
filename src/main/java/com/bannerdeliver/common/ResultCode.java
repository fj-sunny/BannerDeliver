package com.bannerdeliver.common;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** API 统一响应状态码枚举。 */
@Getter
@RequiredArgsConstructor
public enum ResultCode {

    /** 成功。 */
    SUCCESS(0, "success"),
    /** 请求参数不合法。 */
    PARAM_ERROR(400, "参数错误"),
    /** 未认证或认证失败。 */
    AUTH_ERROR(401, "认证失败"),
    /** 已认证但权限不足。 */
    PERMISSION_DENIED(403, "权限不足"),
    /** 一般业务逻辑失败。 */
    BUSINESS_ERROR(1000, "业务处理失败"),
    /** 未预期的服务器内部错误。 */
    INTERNAL_SERVER_ERROR(500, "系统内部错误");

    /** HTTP 风格业务码。 */
    private final int code;
    /** 默认提示文案。 */
    private final String message;
}
