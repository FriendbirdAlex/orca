package com.orca.common.exception;

import com.orca.common.result.Result;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * 全局异常处理。
 * 面试点: 统一兜底, 避免异常栈泄漏给前端; 区分业务异常(可预期, WARN)与系统异常(不可预期, ERROR+打印栈)。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result<Void>> handleBiz(BizException e) {
        log.warn("业务异常 code={} msg={}", e.getCode(), e.getMessage());
        return ResponseEntity.ok(Result.fail(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        log.warn("参数校验失败: {}", msg);
        return ResponseEntity.ok(Result.fail(ErrorCode.PARAM_INVALID.getCode(), msg));
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<Result<Void>> handleBind(BindException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        log.warn("表单绑定校验失败: {}", msg);
        return ResponseEntity.ok(Result.fail(ErrorCode.PARAM_INVALID.getCode(), msg));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Result<Void>> handleConstraint(ConstraintViolationException e) {
        log.warn("约束校验失败: {}", e.getMessage());
        return ResponseEntity.ok(Result.fail(ErrorCode.PARAM_INVALID.getCode(), e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleSystem(Exception e) {
        log.error("系统异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.fail(ErrorCode.SYSTEM_ERROR.getCode(), ErrorCode.SYSTEM_ERROR.getMessage()));
    }
}
