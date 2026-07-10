package com.orca.workflow.dsl;

/**
 * 节点类型。
 * LLM: 调网关 ChatService
 * HTTP: 调外部接口
 * HUMAN: 人工审批(暂停-恢复)
 */
public enum NodeType {
    LLM,
    HTTP,
    HUMAN,
    AGENT
}
