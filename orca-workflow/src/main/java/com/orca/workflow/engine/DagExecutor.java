package com.orca.workflow.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orca.workflow.dsl.DslParser;
import com.orca.workflow.dsl.EdgeDef;
import com.orca.workflow.dsl.NodeDef;
import com.orca.workflow.dsl.WorkflowDsl;
import com.orca.workflow.compensation.SagaCompensator;
import com.orca.workflow.engine.entity.NodeInstance;
import com.orca.workflow.engine.entity.WorkflowInstance;
import com.orca.workflow.engine.mapper.NodeInstanceMapper;
import com.orca.workflow.engine.mapper.WorkflowInstanceMapper;
import com.orca.workflow.event.EventStore;
import com.orca.workflow.event.EventType;
import com.orca.workflow.state.NodeStatus;
import com.orca.workflow.state.StateMachine;
import com.orca.workflow.task.NodeExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DAG 执行器: 拓扑 + 就绪节点调度 + 执行循环。
 *
 * 面考点:
 *  - findReadyNodes: 就绪 = PENDING 且所有上游 SUCCEEDED(入度逻辑)
 *  - executeBatch: 循环执行就绪批次, 失败触发 Saga 补偿, 全成功→SUCCEEDED
 *  - HUMAN 节点返回 _paused → 停止本轮(实例已 PAUSED)
 *  - DSL 缓存: parseAndCache 避免每次推进重复解析
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DagExecutor {

    private final DslParser dslParser;
    private final NodeInstanceMapper nodeInstMapper;
    private final WorkflowInstanceMapper instMapper;
    private final List<NodeExecutor> executors;
    private final EventStore eventStore;
    private final SagaCompensator sagaCompensator;
    private final StateMachine stateMachine;
    private final ObjectMapper objectMapper;

    /** DSL 文本 → 解析结果缓存(同一定义多次推进只解析一次) */
    private final Map<String, WorkflowDsl> dslCache = new ConcurrentHashMap<>();

    public WorkflowDsl parseAndCache(String dslJson) {
        return dslCache.computeIfAbsent(dslJson, dslParser::parse);
    }

    /** 初始化所有节点为 PENDING */
    public void initPendingNodes(Long instanceId, WorkflowDsl dsl) {
        for (NodeDef n : dsl.getNodes()) {
            NodeInstance ni = new NodeInstance();
            ni.setInstanceId(instanceId);
            ni.setNodeId(n.getId());
            ni.setStatus(NodeStatus.PENDING.name());
            ni.setRetryCount(0);
            nodeInstMapper.insert(ni);
        }
    }

    /**
     * 执行一批就绪节点, 循环直到无就绪 / 失败 / 暂停。
     * @return true=已处理到终态(SUCCEEDED/FAILED), false=暂停等恢复
     */
    public boolean executeBatch(WorkflowInstance inst, WorkflowDsl dsl, InstanceContext ctx) {
        List<NodeDef> ready = findReadyNodes(inst.getId(), dsl);
        while (!ready.isEmpty()) {
            for (NodeDef node : ready) {
                NodeInstance ni = nodeInstMapper.selectByInstNode(inst.getId(), node.getId());
                if (ni == null || !NodeStatus.PENDING.name().equals(ni.getStatus())) {
                    continue;   // 已处理(幂等)
                }
                NodeExecutor exec = resolve(node.getType());
                try {
                    // RUNNING + 事件
                    transitNode(ni, NodeStatus.RUNNING);
                    ni.setStartedAt(LocalDateTime.now());
                    nodeInstMapper.updateById(ni);
                    eventStore.append(inst.getId(), EventType.NODE_STARTED, Map.of("nodeId", node.getId()));

                    Map<String, Object> output = exec.execute(node, ctx);

                    // HUMAN 节点: 返回 _paused → 实例已 PAUSED, 停止本轮, 节点保持 RUNNING(等 resume 置 SUCCEEDED)
                    if (output != null && Boolean.TRUE.equals(output.get("_paused"))) {
                        log.info("[dag] 节点暂停实例 node={} instance={}", node.getId(), inst.getId());
                        return false;   // 暂停, 未到终态
                    }

                    // 普通节点: SUCCEEDED
                    ni.setOutput(objectMapper.writeValueAsString(output));
                    ni.setEndedAt(LocalDateTime.now());
                    transitNode(ni, NodeStatus.SUCCEEDED);
                    nodeInstMapper.updateById(ni);
                    ctx.getNodeOutputs().put(node.getId(), output);
                    eventStore.append(inst.getId(), EventType.NODE_SUCCEEDED,
                            Map.of("nodeId", node.getId(), "output", output == null ? Map.of() : output));
                } catch (Exception e) {
                    // 失败 → Saga 补偿
                    ni.setError(truncate(e.getMessage()));
                    ni.setEndedAt(LocalDateTime.now());
                    try { transitNode(ni, NodeStatus.FAILED); } catch (Exception ignored) {}
                    nodeInstMapper.updateById(ni);
                    eventStore.append(inst.getId(), EventType.NODE_FAILED,
                            Map.of("nodeId", node.getId(), "error", String.valueOf(e.getMessage())));
                    log.error("[dag] 节点失败 node={} instance={}", node.getId(), inst.getId(), e);
                    sagaCompensator.compensate(inst.getId(), dsl, ctx);
                    return true;   // 已到终态 FAILED
                }
            }
            ready = findReadyNodes(inst.getId(), dsl);   // 下一批
        }
        // 无就绪且无失败: 检查是否全完成
        if (allSucceeded(inst.getId(), dsl)) {
            inst.setStatus(com.orca.workflow.state.InstanceStatus.SUCCEEDED.name());
            instMapper.updateById(inst);
            eventStore.append(inst.getId(), EventType.COMPLETED, Map.of("result", "SUCCEEDED"));
            log.info("[dag] 实例成功 instance={}", inst.getId());
            return true;
        }
        // 否则可能有待恢复节点(理论不会到此, HUMAN 已 return), 兜底返回未终态
        return false;
    }

    /** 就绪 = PENDING 且 所有上游 SUCCEEDED */
    private List<NodeDef> findReadyNodes(Long instanceId, WorkflowDsl dsl) {
        Map<String, NodeStatus> statusMap = loadStatusMap(instanceId);
        Map<String, List<String>> incoming = buildIncoming(dsl);   // target -> [sources]
        List<NodeDef> ready = new ArrayList<>();
        for (NodeDef n : dsl.getNodes()) {
            if (statusMap.get(n.getId()) != NodeStatus.PENDING) continue;
            List<String> sources = incoming.getOrDefault(n.getId(), List.of());
            boolean allDone = sources.stream().allMatch(src -> statusMap.get(src) == NodeStatus.SUCCEEDED);
            if (allDone) ready.add(n);
        }
        return ready;
    }

    private Map<String, NodeStatus> loadStatusMap(Long instanceId) {
        Map<String, NodeStatus> map = new HashMap<>();
        for (NodeInstance ni : nodeInstMapper.selectByInstance(instanceId)) {
            try { map.put(ni.getNodeId(), NodeStatus.valueOf(ni.getStatus())); }
            catch (Exception ignored) { map.put(ni.getNodeId(), NodeStatus.PENDING); }
        }
        return map;
    }

    private Map<String, List<String>> buildIncoming(WorkflowDsl dsl) {
        Map<String, List<String>> incoming = new HashMap<>();
        if (dsl.getEdges() != null) {
            for (EdgeDef e : dsl.getEdges()) {
                incoming.computeIfAbsent(e.getTarget(), k -> new ArrayList<>()).add(e.getSource());
            }
        }
        return incoming;
    }

    private boolean allSucceeded(Long instanceId, WorkflowDsl dsl) {
        Map<String, NodeStatus> statusMap = loadStatusMap(instanceId);
        return dsl.getNodes().stream().allMatch(n -> statusMap.get(n.getId()) == NodeStatus.SUCCEEDED);
    }

    private void transitNode(NodeInstance ni, NodeStatus to) {
        NodeStatus from = NodeStatus.valueOf(ni.getStatus());
        stateMachine.checkNode(from, to);
        ni.setStatus(to.name());
    }

    private NodeExecutor resolve(com.orca.workflow.dsl.NodeType type) {
        return executors.stream()
                .filter(e -> e.supportType() == type)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("无执行器 for " + type));
    }

    private String truncate(String s) {
        if (s == null) return null;
        return s.length() > 500 ? s.substring(0, 500) : s;
    }
}
