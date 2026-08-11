package com.bannerdeliver.exception;

import com.bannerdeliver.common.Result;
import com.bannerdeliver.common.ResultCode;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** 全局异常处理，将各类异常转换为统一的 Result 响应。 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 处理参数业务异常，返回 400。 */
    @ExceptionHandler(ParamException.class)
    public ResponseEntity<Result<Void>> handleParamException(ParamException exception) {
        return ResponseEntity.badRequest()
                .body(Result.failure(exception.getResultCode(), exception.getMessage()));
    }

    /** 处理认证失败异常，返回 401。 */
    @ExceptionHandler(AuthException.class)
    public ResponseEntity<Result<Void>> handleAuthException(AuthException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Result.failure(exception.getResultCode(), exception.getMessage()));
    }

    /** 处理权限不足异常，返回 403。 */
    @ExceptionHandler(PermissionDeniedException.class)
    public ResponseEntity<Result<Void>> handlePermissionDeniedException(
            PermissionDeniedException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Result.failure(exception.getResultCode(), exception.getMessage()));
    }

    /** 处理一般业务异常，返回 400。 */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusinessException(BusinessException exception) {
        return ResponseEntity.badRequest()
                .body(Result.failure(exception.getResultCode(), exception.getMessage()));
    }

    /** 处理 @Valid 请求体校验失败。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValidationException(
            MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .orElse(ResultCode.PARAM_ERROR.getMessage());
        return ResponseEntity.badRequest().body(Result.failure(ResultCode.PARAM_ERROR, message));
    }

    /** 处理 Bean Validation 约束违反异常。 */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Result<Void>> handleConstraintViolation(
            ConstraintViolationException exception) {
        String message = exception.getConstraintViolations().stream()
                .findFirst()
                .map(violation -> violation.getPropertyPath() + ": "
                        + violation.getMessage())
                .orElse(ResultCode.PARAM_ERROR.getMessage());
        return ResponseEntity.badRequest().body(Result.failure(ResultCode.PARAM_ERROR, message));
    }

    /** 处理 Controller 方法参数校验失败（Spring 6.1+）。 */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<Result<Void>> handleHandlerMethodValidation(
            HandlerMethodValidationException exception) {
        return ResponseEntity.badRequest()
                .body(Result.failure(ResultCode.PARAM_ERROR, "请求参数校验失败"));
    }

    /** 处理缺少必填请求参数的情况。 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Result<Void>> handleMissingRequestParameter(
            MissingServletRequestParameterException exception) {
        return ResponseEntity.badRequest().body(Result.failure(
                ResultCode.PARAM_ERROR,
                exception.getParameterName() + ": 参数不能为空"));
    }

    /** 处理请求参数类型不匹配的情况。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<Void>> handleArgumentTypeMismatch(
            MethodArgumentTypeMismatchException exception) {
        return ResponseEntity.badRequest().body(Result.failure(
                ResultCode.PARAM_ERROR,
                exception.getName() + ": 参数类型错误"));
    }

    /** 兜底处理未捕获异常，返回 500 并记录 error 日志。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleUnexpectedException(Exception exception) {
        log.error("Unhandled exception", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.failure(ResultCode.INTERNAL_SERVER_ERROR));
    }
}
