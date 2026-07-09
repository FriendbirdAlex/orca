package com.orca.workflow.state;

/**
 * 实例状态机。
 * RUNNING: 执行中(调度器会推进)
 * PAUSED:  人工审批暂停(等 decide/超时)
 * SUCCEEDED: 全节点成功, 终态
 * FAILED:  节点失败经 Saga 补偿后, 终态
 */
public enum InstanceStatus {
    RUNNING,
    PAUSED,
    SUCCEEDED,
    FAILED
}
