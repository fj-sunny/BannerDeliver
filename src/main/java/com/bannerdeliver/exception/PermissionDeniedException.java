package com.bannerdeliver.exception;

import com.bannerdeliver.common.ResultCode;

public class PermissionDeniedException extends BaseException {

    public PermissionDeniedException(String message) {
        super(ResultCode.PERMISSION_DENIED, message);
    }
}
