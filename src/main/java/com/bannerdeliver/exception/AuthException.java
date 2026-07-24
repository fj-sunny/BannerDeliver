package com.bannerdeliver.exception;

import com.bannerdeliver.common.ResultCode;

public class AuthException extends BaseException {

    public AuthException(String message) {
        super(ResultCode.AUTH_ERROR, message);
    }
}
