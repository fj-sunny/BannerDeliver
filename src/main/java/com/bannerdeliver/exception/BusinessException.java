package com.bannerdeliver.exception;

import com.bannerdeliver.common.ResultCode;

/** 一般业务处理失败异常。 */
public class BusinessException extends BaseException {

    /** 使用自定义业务错误消息构造异常。 */
    public BusinessException(String message) {
        super(ResultCode.BUSINESS_ERROR, message);
    }
}
