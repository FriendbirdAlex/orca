package com.orca.workflow.dsl;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

/**
 * 节点定义。
 * config: 类型相关配置(JsonNode, 保留原始结构按 type 解析):
 *   LLM:   {model, messages:[{role,content}], maxTokens, temperature}
 *   HTTP:  {url, method, headers, body, compensateUrl?}
 *   HUMAN: {timeoutMinutes, assignee}
 */
@Data
public class NodeDef {
    private String id;
    private NodeType type;
    private String name;
    private JsonNode config;
}
