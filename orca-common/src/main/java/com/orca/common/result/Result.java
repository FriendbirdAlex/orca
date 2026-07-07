package com.orca.common.result;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 统一响应体。
 * 面试点: 为什么不用 Map 返回 → 类型安全 + 前端协议统一 + 可扩展 code/data/traceId。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Result<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 业务码: 0 成功, 非 0 失败 */
    private int code;
    /** 提示信息 */
    private String message;
    /** 业务数据 */
    private T data;
    /** 链路追踪 ID, 便于排障 */
    private String traceId;

    public static <T> Result<T> success(T data) {
        return new Result<>(0, "ok", data, null);
    }

    public static <T> Result<T> fail(int code, String message) {
        return new Result<>(code, message, null, null);
    }

    public boolean isSuccess() {
        return code == 0;
    }
}
