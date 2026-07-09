package com.orca.workflow.state;

import com.orca.common.exception.BizException;
import com.orca.common.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * 状态机: 白名单转换, 非法转换抛异常(防并发/乱序推进)。
 *
 * 面试点: 状态机保证只允许合法迁移, 如 SUCCEEDED/FAILED 是终态不可再变,
 *        并发场景下两个线程同时推进同一节点会被状态校验拦下。
 */
@Component
public class StateMachine {

    private static final Map<InstanceStatus, Set<InstanceStatus>> INSTANCE_TRANSITIONS = Map.of(
            InstanceStatus.RUNNING, Set.of(InstanceStatus.PAUSED, InstanceStatus.SUCCEEDED, InstanceStatus.FAILED),
            InstanceStatus.PAUSED, Set.of(InstanceStatus.RUNNING),                 // resume
            InstanceStatus.SUCCEEDED, Set.of(),
            InstanceStatus.FAILED, Set.of());

    private static final Map<NodeStatus, Set<NodeStatus>> NODE_TRANSITIONS = Map.of(
            NodeStatus.PENDING, Set.of(NodeStatus.RUNNING, NodeStatus.SKIPPED),
            NodeStatus.RUNNING, Set.of(NodeStatus.SUCCEEDED, NodeStatus.FAILED),
            NodeStatus.SUCCEEDED, Set.of(),
            NodeStatus.FAILED, Set.of(),
            NodeStatus.SKIPPED, Set.of());

    public void checkInstance(InstanceStatus from, InstanceStatus to) {
        Set<InstanceStatus> allowed = INSTANCE_TRANSITIONS.get(from);
        if (allowed == null || !allowed.contains(to)) {
            throw new BizException(ErrorCode.WF_INVALID_DSL, "非法实例状态转换: " + from + "->" + to);
        }
    }

    public void checkNode(NodeStatus from, NodeStatus to) {
        Set<NodeStatus> allowed = NODE_TRANSITIONS.get(from);
        if (allowed == null || !allowed.contains(to)) {
            throw new BizException(ErrorCode.WF_INVALID_DSL, "非法节点状态转换: " + from + "->" + to);
        }
    }
}
