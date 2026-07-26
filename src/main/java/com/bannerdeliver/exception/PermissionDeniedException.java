package com.bannerdeliver.exception;

import com.bannerdeliver.common.ResultCode;

/** 用户权限不足异常。 */
public class PermissionDeniedException extends BaseException {

    /** 使用自定义权限不足消息构造异常。 */
    public PermissionDeniedException(String message) {
        super(ResultCode.PERMISSION_DENIED, message);
    }
}
