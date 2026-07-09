package com.orca.workflow.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orca.workflow.config.WorkflowProperties;
import com.orca.workflow.dsl.NodeDef;
import com.orca.workflow.dsl.NodeType;
import com.orca.workflow.engine.InstanceContext;
import com.orca.workflow.event.EventStore;
import com.orca.workflow.event.EventType;
import com.orca.workflow.engine.entity.WorkflowInstance;
import com.orca.workflow.engine.mapper.WorkflowInstanceMapper;
import com.orca.workflow.scheduler.entity.HumanTask;
import com.orca.workflow.scheduler.mapper.HumanTaskMapper;
import com.orca.workflow.state.InstanceStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 人工审批节点执行器(Human-in-loop)。
 *
 * 暂停-恢复-超时机制:
 *  - 暂停: execute 建 human_task(PENDING+timeout_at) + 实例 PAUSED + PAUSED 事件, 返回 _paused 标记
 *         DagExecutor 检测 _paused 后停止本轮(不置节点 SUCCEEDED)
 *  - 恢复: 人 POST /decide(APPROVED) → engine.resume(PAUSED→RUNNING) → DagExecutor 把该节点置 SUCCEEDED → 继续
 *         REJECTED → 节点失败 → Saga → FAILED
 *  - 超时: scheduler 扫 PENDING&timeout_at<=now → TIMEOUT → 节点失败 → Saga → FAILED
 *
 * 无副作用, compensate 空。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HumanTaskNodeExecutor implements NodeExecutor {

    private final HumanTaskMapper humanTaskMapper;
    private final WorkflowInstanceMapper instanceMapper;
    private final EventStore eventStore;
    private final WorkflowProperties properties;
    private final ObjectMapper objectMapper;

    @Override
    public NodeType supportType() {
        return NodeType.HUMAN;
    }

    @Override
    public Map<String, Object> execute(NodeDef node, InstanceContext ctx) throws Exception {
        JsonNode cfg = node.getConfig();
        int timeoutMin = cfg.path("timeoutMinutes").asInt(properties.getHumanTaskDefaultTimeoutMinutes());

        // 1. 创建 human_task
        HumanTask ht = new HumanTask();
        ht.setInstanceId(ctx.getInstanceId());
        ht.setNodeId(node.getId());
        ht.setStatus("PENDING");
        ht.setTimeoutAt(LocalDateTime.now().plusMinutes(timeoutMin));
        ht.setAssignee(cfg.path("assignee").asText(null));
        ht.setPayload(buildApprovalPayload(node, ctx));
        humanTaskMapper.insert(ht);

        // 2. 实例 PAUSED + PAUSED 事件
        WorkflowInstance inst = instanceMapper.selectById(ctx.getInstanceId());
        inst.setStatus(InstanceStatus.PAUSED.name());
        instanceMapper.updateById(inst);
        eventStore.append(ctx.getInstanceId(), EventType.PAUSED,
                Map.of("nodeId", node.getId(), "humanTaskId", ht.getId()));
        log.info("[human-node] 暂停实例 node={} instance={} humanTask={} timeoutAt={}",
                node.getId(), ctx.getInstanceId(), ht.getId(), ht.getTimeoutAt());

        // 3. 返回 _paused 标记, DagExecutor 检测后停止本轮
        Map<String, Object> output = new HashMap<>();
        output.put("_paused", true);
        output.put("humanTaskId", ht.getId());
        return output;
    }

    @Override
    public void compensate(NodeDef node, InstanceContext ctx, com.orca.workflow.engine.entity.NodeInstance ni) {
        // 人工节点无副作用, 无需补偿
    }

    private String buildApprovalPayload(NodeDef node, InstanceContext ctx) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("nodeName", node.getName());
            payload.put("nodeId", node.getId());
            payload.put("instanceId", ctx.getInstanceId());
            payload.put("context", ctx.getNodeOutputs());
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return "{}";
        }
    }
}
