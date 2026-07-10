package com.orca.agent.runtime;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Agent 定义。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentDefinition {
    private String name;            // agent 名
    private String systemPrompt;    // 角色提示
    private List<String> tools;     // 允许的工具名
    private Integer maxSteps;       // null 用 AgentProperties 默认
    private Long maxTokens;         // null 用默认
}
