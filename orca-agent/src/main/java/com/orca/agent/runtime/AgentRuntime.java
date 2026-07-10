package com.orca.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orca.agent.config.AgentProperties;
import com.orca.agent.react.ReActLoop;
import com.orca.agent.react.ReActResult;
import com.orca.common.context.TenantContext;
import com.orca.common.context.TenantInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Agent 运行时入口: 组装 ReActLoop + Memory + ToolRegistry。
 *
 * TenantContext 重建(与 LlmNodeExecutor 同范式):
 *  - 工作流场景: 传 tenantSnapshot, 反序列化重建(异步线程无 ThreadLocal)
 *  - 独立接口: Controller 线程已有 TenantContext, snapshot 传 null 走 require
 *  必须 try-finally clear(虚拟线程池复用防串租户)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentRuntime {

    private final ReActLoop reActLoop;
    private final AgentProperties props;
    private final ObjectMapper objectMapper;

    /** 完整入口: 工作流 Agent 节点用(传 tenantSnapshot 重建) */
    public ReActResult run(AgentDefinition agent, String query, String tenantSnapshot) {
        TenantInfo t;
        if (tenantSnapshot != null) {
            try {
                t = objectMapper.readValue(tenantSnapshot, TenantInfo.class);
            } catch (Exception e) {
                t = TenantContext.require();
            }
        } else {
            t = TenantContext.require();
        }
        TenantContext.set(t);
        try {
            String runId = UUID.randomUUID().toString().replace("-", "");
            int maxSteps = agent.getMaxSteps() != null ? agent.getMaxSteps() : props.getMaxSteps();
            long maxTokens = agent.getMaxTokens() != null ? agent.getMaxTokens() : props.getMaxTokens();
            log.info("[agent] 运行 name={} runId={} tenant={} query={}",
                    agent.getName(), runId, t.tenantId(), query);
            return reActLoop.loop(query, agent.getTools(), agent.getSystemPrompt(),
                    runId, maxSteps, maxTokens);
        } finally {
            TenantContext.clear();
        }
    }

    /** 简化重载: 独立接口用(Controller 线程已有 TenantContext) */
    public ReActResult run(AgentDefinition agent, String query) {
        return run(agent, query, null);
    }
}
