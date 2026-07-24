package com.bannerdeliver.exception;

import com.bannerdeliver.common.ResultCode;

public class ParamException extends BaseException {

    public ParamException(String message) {
        super(ResultCode.PARAM_ERROR, message);
    }
}
