package com.orca.agent.controller;

import com.orca.agent.controller.dto.AgentRunRequest;
import com.orca.agent.controller.dto.AgentRunResponse;
import com.orca.agent.runtime.AgentDefinition;
import com.orca.agent.runtime.AgentRuntime;
import com.orca.common.result.Result;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Agent REST 接口。
 * /v1/** 被 AuthInterceptor 拦截, Controller 线程自动有 TenantContext(AgentRuntime 直接用)。
 *
 * 同步运行: Agent 是短任务(2-5 步, 每步秒级), 同步返回 finalAnswer + steps。
 * 长时 Agent 走工作流 AGENT 节点(异步 + 事件溯源)。
 */
@Slf4j
@RestController
@RequestMapping("/v1/agents")
@RequiredArgsConstructor
public class AgentController {

    private final AgentRuntime agentRuntime;

    @PostMapping("/run")
    public Result<AgentRunResponse> run(@RequestBody @Valid AgentRunRequest req) {
        AgentDefinition agent = AgentDefinition.builder()
                .name(req.getAgentName() != null ? req.getAgentName() : "default")
                .systemPrompt(req.getSystemPrompt() != null ? req.getSystemPrompt() : "你是一个助手")
                .tools(req.getTools() != null ? req.getTools() : List.of())
                .maxSteps(req.getMaxSteps())
                .maxTokens(req.getMaxTokens())
                .build();
        return Result.success(AgentRunResponse.from(agentRuntime.run(agent, req.getQuery())));
    }
}
