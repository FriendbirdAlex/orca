package com.orca.common.exception;

import lombok.Getter;

/**
 * 业务异常基类。
 * 面试点: 受检 vs 非受检 → 业务异常用 RuntimeException(非受检), 不强制 try-catch 污染调用栈;
 *       系统异常单独走兜底 Handler。
 */
@Getter
public class BizException extends RuntimeException {

    private final int code;

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BizException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
    }

    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.code = errorCode.getCode();
    }
}
