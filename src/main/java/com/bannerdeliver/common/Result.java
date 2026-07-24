package com.bannerdeliver.common;

/**
 * API 统一响应结构。
 */
public record Result<T>(int code, String message, T data) {

    public static <T> Result<T> success(T data) {
        return new Result<>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMessage(), data);
    }

    public static Result<Void> success() {
        return success(null);
    }

    public static Result<Void> failure(ResultCode resultCode) {
        return failure(resultCode, resultCode.getMessage());
    }

    public static Result<Void> failure(ResultCode resultCode, String message) {
        return new Result<>(resultCode.getCode(), message, null);
    }
}
