package com.bannerdeliver.exception;

import com.bannerdeliver.common.ResultCode;

/** 用户认证失败异常。 */
public class AuthException extends BaseException {

    /** 使用自定义认证失败消息构造异常。 */
    public AuthException(String message) {
        super(ResultCode.AUTH_ERROR, message);
    }
}
