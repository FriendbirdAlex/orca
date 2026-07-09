package com.orca.workflow.compensation;

import com.orca.workflow.dsl.NodeDef;
import com.orca.workflow.dsl.WorkflowDsl;
import com.orca.workflow.engine.InstanceContext;
import com.orca.workflow.engine.entity.NodeInstance;
import com.orca.workflow.engine.entity.WorkflowInstance;
import com.orca.workflow.engine.mapper.NodeInstanceMapper;
import com.orca.workflow.engine.mapper.WorkflowInstanceMapper;
import com.orca.workflow.event.EventStore;
import com.orca.workflow.event.EventType;
import com.orca.workflow.state.InstanceStatus;
import com.orca.workflow.task.NodeExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Saga 补偿器: 节点失败后, 取所有 SUCCEEDED 节点逆序补偿, 最终实例 FAILED。
 *
 * 面考点(Saga vs 2PC/TCC):
 *  - 2PC: 强一致, 协调者单点 + 阻塞, 不适合长事务(LLM 调用秒级)
 *  - TCC: Try-Confirm-Cancel, 业务侵入大, 每节点三套实现
 *  - Saga: 正向执行 + 失败逆序补偿, 最终一致, 适合长流程, 业务侵入小(只补一个 compensate 方法)
 *  代价: 中间状态可见(已发 LLM 无法收回, 仅记录)
 *
 * 补偿失败不中断, 继续补偿其余节点(尽力而为), 最终标记是否需人工介入。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SagaCompensator {

    private final NodeInstanceMapper nodeInstMapper;
    private final WorkflowInstanceMapper instMapper;
    private final EventStore eventStore;
    private final List<NodeExecutor> executors;

    public void compensate(Long instanceId, WorkflowDsl dsl, InstanceContext ctx) {
        // 1. 取所有 SUCCEEDED 节点(按 ended_at DESC 已是逆序)
        List<NodeInstance> done = nodeInstMapper.selectSucceededByInstance(instanceId);
        if (done == null || done.isEmpty()) {
            log.info("[saga] 无需补偿(无已完成节点) instance={}", instanceId);
            finishFailed(instanceId, false);
            return;
        }
        // selectSucceededByInstance 已 ORDER BY ended_at DESC, 即逆序(后完成先补偿)
        List<NodeInstance> reversed = done;   // 已逆序

        boolean compensateFailed = false;
        for (NodeInstance ni : reversed) {
            NodeDef node = dsl.getNodes().stream()
                    .filter(n -> n.getId().equals(ni.getNodeId()))
                    .findFirst().orElse(null);
            if (node == null) continue;
            NodeExecutor exec = resolve(node);
            try {
                exec.compensate(node, ctx, ni);
                eventStore.append(instanceId, EventType.COMPENSATED, Map.of("nodeId", node.getId()));
                log.info("[saga] 补偿成功 node={} instance={}", node.getId(), instanceId);
            } catch (Exception e) {
                compensateFailed = true;
                log.error("[saga] 补偿失败 node={} instance={}", node.getId(), instanceId, e);
                eventStore.append(instanceId, EventType.COMPENSATE_FAILED,
                        Map.of("nodeId", node.getId(), "error", e.getMessage()));
                // 继续补偿其余节点(尽力而为)
            }
        }
        finishFailed(instanceId, compensateFailed);
    }

    private void finishFailed(Long instanceId, boolean compensateFailed) {
        WorkflowInstance inst = instMapper.selectById(instanceId);
        inst.setStatus(InstanceStatus.FAILED.name());
        instMapper.updateById(inst);
        eventStore.append(instanceId, EventType.COMPLETED,
                Map.of("result", "FAILED", "compensateFailed", compensateFailed));
        log.info("[saga] 实例结束 FAILED instance={} compensateFailed={}", instanceId, compensateFailed);
    }

    private NodeExecutor resolve(NodeDef node) {
        return executors.stream()
                .filter(e -> e.supportType() == node.getType())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("无执行器 for " + node.getType()));
    }
}
