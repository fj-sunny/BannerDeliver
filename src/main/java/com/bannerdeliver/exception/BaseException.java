package com.bannerdeliver.exception;

import com.bannerdeliver.common.ResultCode;
import lombok.Getter;

/** 业务异常基类，携带统一的 ResultCode。 */
@Getter
public abstract class BaseException extends RuntimeException {

    private final ResultCode resultCode;

    /** 使用 ResultCode 默认消息构造异常。 */
    protected BaseException(ResultCode resultCode) {
        this(resultCode, resultCode.getMessage());
    }

    /** 使用自定义消息构造异常。 */
    protected BaseException(ResultCode resultCode, String message) {
        super(message);
        this.resultCode = resultCode;
    }
}
