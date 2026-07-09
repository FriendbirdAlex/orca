package com.orca.workflow.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orca.common.context.TenantContext;
import com.orca.common.context.TenantInfo;
import com.orca.common.exception.BizException;
import com.orca.common.exception.ErrorCode;
import com.orca.common.result.Result;
import com.orca.gateway.provider.model.ChatRequest;
import com.orca.gateway.provider.model.ChatResponse;
import com.orca.gateway.service.ChatService;
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
 * LLM 节点执行器: 调网关 ChatService(唯一出口, 白嫖限流/配额/熔断/缓存/记账)。
 *
 * ★ 全方案最关键 — TenantContext 快照重建:
 *  ChatService.chat() 首行 TenantContext.require(), 但工作流调度/异步线程无 ThreadLocal。
 *  解法: 从 ctx.tenantSnapshot(创建实例时 Controller 线程捕获的 JSON)反序列化 → set → 调用 → finally clear。
 *  必须 try-finally clear: 虚拟线程池复用, 不清理会串租户(越权)。
 *
 * 面考点: 异步线程 ThreadLocal 不传递 + 虚拟线程每线程独立副本 → 快照重建 + 显式 set/clear。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmNodeExecutor implements NodeExecutor {

    private final ChatService chatService;
    private final ObjectMapper objectMapper;

    @Override
    public NodeType supportType() {
        return NodeType.LLM;
    }

    @Override
    public Map<String, Object> execute(NodeDef node, InstanceContext ctx) throws Exception {
        JsonNode config = node.getConfig();
        // ★ 重建 TenantContext
        TenantInfo t = objectMapper.readValue(ctx.getTenantSnapshot(), TenantInfo.class);
        TenantContext.set(t);
        try {
            ChatRequest req = buildRequest(config, ctx);
            log.info("[llm-node] 执行 node={} tenant={} model={}", node.getId(), t.tenantId(), req.getModel());
            Result<ChatResponse> resp = chatService.chat(req);
            if (resp == null || !resp.isSuccess() || resp.getData() == null) {
                throw new BizException(ErrorCode.WF_NODE_FAILED,
                        "LLM 调用失败: " + (resp == null ? "null" : resp.getMessage()));
            }
            String text = resp.getData().getChoices().get(0).getMessage().getContent();
            Map<String, Object> output = new HashMap<>();
            output.put("text", text);
            output.put("usage", objectMapper.convertValue(resp.getData().getUsage(), Map.class));
            return output;
        } finally {
            // ★ 必须清理, 防虚拟线程池复用串租户
            TenantContext.clear();
        }
    }

    @Override
    public void compensate(NodeDef node, InstanceContext ctx, NodeInstance ni) {
        // LLM 补偿: 已发送的请求/已记账无法回滚, 仅记录(面试点: 不可逆资源只能记录, 不能真回滚)
        log.info("[llm-node] 补偿(仅记录, 不可回滚) node={} instance={}", node.getId(), ctx.getInstanceId());
    }

    private ChatRequest buildRequest(JsonNode config, InstanceContext ctx) {
        ChatRequest req = new ChatRequest();
        req.setModel(config.path("model").asText("mock"));
        if (config.has("maxTokens")) req.setMaxTokens(config.get("maxTokens").asInt(1024));
        if (config.has("temperature")) req.setTemperature(config.get("temperature").asDouble(0.7));

        List<ChatRequest.Message> messages = new ArrayList<>();
        JsonNode msgsNode = config.path("messages");
        if (msgsNode.isArray()) {
            for (JsonNode m : msgsNode) {
                ChatRequest.Message msg = new ChatRequest.Message();
                msg.setRole(m.path("role").asText("user"));
                // content 模板渲染: ${prevNode.text} / ${input.x}
                msg.setContent(ctx.render(m.path("content").asText("")));
                messages.add(msg);
            }
        }
        req.setMessages(messages);
        return req;
    }
}
