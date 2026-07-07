package com.orca.common.exception;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 错误码枚举。
 * 约定: 6 位, 前两位模块(10 通用 / 20 网关 / 30 工作流 / 40 Agent), 后四位具体错误。
 */
@Getter
@AllArgsConstructor
public enum ErrorCode {

    SUCCESS(0, "success"),

    // 通用 10xxxx
    PARAM_INVALID(100001, "参数校验失败"),
    SYSTEM_ERROR(100500, "系统异常"),
    UNAUTHORIZED(100401, "未授权"),
    FORBIDDEN(100403, "无权限"),

    // 网关 20xxxx
    GW_LIMITED(200001, "请求被限流"),
    GW_QUOTA_EXCEEDED(200002, "配额已用尽"),
    GW_CIRCUIT_OPEN(200003, "熔断器开启, 服务暂不可用"),
    GW_NO_PROVIDER(200004, "无可用 Provider"),
    GW_UPSTREAM_ERROR(200500, "上游模型调用失败"),

    // 工作流 30xxxx
    WF_NOT_FOUND(300001, "工作流不存在"),
    WF_INSTANCE_NOT_FOUND(300002, "工作流实例不存在"),
    WF_INVALID_DSL(300003, "DSL 非法"),
    WF_NODE_FAILED(300004, "节点执行失败"),
    WF_HUMAN_TASK_TIMEOUT(300005, "人工审批超时"),

    // Agent 40xxxx
    AGENT_LOOP_LIMIT(400001, "Agent 超出循环步数上限"),
    AGENT_COST_LIMIT(400002, "Agent 超出成本上限"),
    AGENT_TOOL_ERROR(400003, "工具执行异常"),
    ;

    private final int code;
    private final String message;
}
