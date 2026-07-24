package com.bannerdeliver.exception;

import com.bannerdeliver.common.ResultCode;
import lombok.Getter;

@Getter
public abstract class BaseException extends RuntimeException {

    private final ResultCode resultCode;

    protected BaseException(ResultCode resultCode) {
        this(resultCode, resultCode.getMessage());
    }

    protected BaseException(ResultCode resultCode, String message) {
        super(message);
        this.resultCode = resultCode;
    }
}
