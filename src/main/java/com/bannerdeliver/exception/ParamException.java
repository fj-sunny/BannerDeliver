package com.bannerdeliver.exception;

import com.bannerdeliver.common.ResultCode;

/** 请求参数不合法异常。 */
public class ParamException extends BaseException {

    /** 使用自定义参数错误消息构造异常。 */
    public ParamException(String message) {
        super(ResultCode.PARAM_ERROR, message);
    }
}
