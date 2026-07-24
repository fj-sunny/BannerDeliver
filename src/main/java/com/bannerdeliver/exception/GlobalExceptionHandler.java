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

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ParamException.class)
    public ResponseEntity<Result<Void>> handleParamException(ParamException exception) {
        return ResponseEntity.badRequest()
                .body(Result.failure(exception.getResultCode(), exception.getMessage()));
    }

    @ExceptionHandler(AuthException.class)
    public ResponseEntity<Result<Void>> handleAuthException(AuthException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Result.failure(exception.getResultCode(), exception.getMessage()));
    }

    @ExceptionHandler(PermissionDeniedException.class)
    public ResponseEntity<Result<Void>> handlePermissionDeniedException(
            PermissionDeniedException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Result.failure(exception.getResultCode(), exception.getMessage()));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusinessException(BusinessException exception) {
        return ResponseEntity.badRequest()
                .body(Result.failure(exception.getResultCode(), exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValidationException(
            MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .orElse(ResultCode.PARAM_ERROR.getMessage());
        return ResponseEntity.badRequest().body(Result.failure(ResultCode.PARAM_ERROR, message));
    }

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

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<Result<Void>> handleHandlerMethodValidation(
            HandlerMethodValidationException exception) {
        return ResponseEntity.badRequest()
                .body(Result.failure(ResultCode.PARAM_ERROR, "请求参数校验失败"));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Result<Void>> handleMissingRequestParameter(
            MissingServletRequestParameterException exception) {
        return ResponseEntity.badRequest().body(Result.failure(
                ResultCode.PARAM_ERROR,
                exception.getParameterName() + ": 参数不能为空"));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<Void>> handleArgumentTypeMismatch(
            MethodArgumentTypeMismatchException exception) {
        return ResponseEntity.badRequest().body(Result.failure(
                ResultCode.PARAM_ERROR,
                exception.getName() + ": 参数类型错误"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleUnexpectedException(Exception exception) {
        log.error("Unhandled exception", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.failure(ResultCode.INTERNAL_SERVER_ERROR));
    }
}
