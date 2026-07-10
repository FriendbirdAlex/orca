package com.orca.workflow.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orca.agent.react.ReActResult;
import com.orca.agent.runtime.AgentDefinition;
import com.orca.agent.runtime.AgentRuntime;
import com.orca.common.context.TenantContext;
import com.orca.common.context.TenantInfo;
import com.orca.workflow.dsl.NodeDef;
import com.orca.workflow.dsl.NodeType;
import com.orca.workflow.engine.InstanceContext;
import com.orca.workflow.engine.entity.NodeInstance;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 节点执行器: Agent 作为工作流一等节点(ReAct 循环)。
 *
 * ★ TenantContext 重建(照搬 LlmNodeExecutor): 工作流调度/异步线程无 ThreadLocal,
 *  从 ctx.tenantSnapshot 反序列化 → set → 调 AgentRuntime → finally clear(防虚拟线程复用串租户)。
 *
 * config:
 *   {agentName, systemPrompt, tools:[weather,...], maxSteps?, maxTokens?, query}
 *   query 支持模板渲染: ${input.x} / ${prevNode.finalAnswer}
 *
 * OCP: 新增 @Component, DagExecutor.resolve(AGENT) 自动路由, 引擎零改。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentNodeExecutor implements NodeExecutor {

    private final AgentRuntime agentRuntime;
    private final ObjectMapper objectMapper;

    @Override
    public NodeType supportType() {
        return NodeType.AGENT;
    }

    @Override
    public Map<String, Object> execute(NodeDef node, InstanceContext ctx) throws Exception {
        JsonNode config = node.getConfig();
        // ★ 重建 TenantContext(与 LlmNodeExecutor 一致)
        TenantInfo t = objectMapper.readValue(ctx.getTenantSnapshot(), TenantInfo.class);
        TenantContext.set(t);
        try {
            AgentDefinition agent = AgentDefinition.builder()
                    .name(config.path("agentName").asText("default"))
                    .systemPrompt(config.path("systemPrompt").asText("你是一个助手"))
                    .tools(parseTools(config.path("tools")))
                    .maxSteps(config.has("maxSteps") ? config.get("maxSteps").asInt() : null)
                    .maxTokens(config.has("maxTokens") ? config.get("maxTokens").asLong() : null)
                    .build();
            // query 模板渲染: ${input.query} / ${researchAgent.finalAnswer}
            String query = ctx.render(config.path("query").asText(""));

            log.info("[agent-node] 执行 node={} tenant={} agent={}", node.getId(), t.tenantId(), agent.getName());
            ReActResult result = agentRuntime.run(agent, query, ctx.getTenantSnapshot());

            Map<String, Object> output = new HashMap<>();
            output.put("finalAnswer", result.getFinalAnswer());
            output.put("steps", result.getSteps());
            output.put("totalTokens", result.getTotalTokens());
            output.put("truncated", result.isTruncated());
            return output;
        } finally {
            TenantContext.clear();
        }
    }

    @Override
    public void compensate(NodeDef node, InstanceContext ctx, NodeInstance ni) {
        // Agent 补偿: LLM 调用/工具副作用不可逆, 仅记录(同 LlmNodeExecutor 语义)
        log.info("[agent-node] 补偿(仅记录) node={} instance={}", node.getId(), ctx.getInstanceId());
    }

    private List<String> parseTools(JsonNode toolsNode) {
        List<String> tools = new ArrayList<>();
        if (toolsNode != null && toolsNode.isArray()) {
            for (JsonNode tn : toolsNode) tools.add(tn.asText());
        }
        return tools;
    }
}
