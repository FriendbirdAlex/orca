package com.orca.workflow.event;

/**
 * 工作流事件类型(事件溯源)。
 */
public enum EventType {
    STARTED,
    NODE_STARTED,
    NODE_SUCCEEDED,
    NODE_FAILED,
    PAUSED,
    RESUMED,
    HUMAN_APPROVED,
    HUMAN_REJECTED,
    HUMAN_TASK_TIMEOUT,
    COMPENSATED,
    COMPENSATE_FAILED,
    COMPLETED
}
