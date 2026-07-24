package com.bannerdeliver.exception;

import com.bannerdeliver.common.ResultCode;

public class BusinessException extends BaseException {

    public BusinessException(String message) {
        super(ResultCode.BUSINESS_ERROR, message);
    }
}
