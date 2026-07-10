package com.orca.agent.controller.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

@Data
public class AgentRunRequest {
    @NotBlank(message = "query 不能为空")
    private String query;

    private String agentName;

    private String systemPrompt;

    /** 允许的工具名列表 */
    private List<String> tools;

    private Integer maxSteps;

    private Long maxTokens;
}
