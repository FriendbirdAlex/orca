package com.orca.workflow.controller;

import com.orca.common.exception.BizException;
import com.orca.common.exception.ErrorCode;
import com.orca.common.result.Result;
import com.orca.workflow.controller.dto.CreateDefRequest;
import com.orca.workflow.controller.dto.DecideRequest;
import com.orca.workflow.controller.dto.StartRequest;
import com.orca.workflow.dsl.DslParser;
import com.orca.workflow.dsl.entity.WorkflowDefinition;
import com.orca.workflow.dsl.mapper.WorkflowDefinitionMapper;
import com.orca.workflow.engine.WorkflowEngine;
import com.orca.workflow.engine.entity.NodeInstance;
import com.orca.workflow.engine.entity.WorkflowInstance;
import com.orca.workflow.engine.mapper.NodeInstanceMapper;
import com.orca.workflow.engine.mapper.WorkflowInstanceMapper;
import com.orca.workflow.event.EventStore;
import com.orca.workflow.event.entity.WorkflowEvent;
import com.orca.workflow.scheduler.entity.HumanTask;
import com.orca.workflow.scheduler.mapper.HumanTaskMapper;
import com.orca.workflow.state.NodeStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作流 REST 接口。
 * 路径 /v1/workflow/** 被 AuthInterceptor(拦 /v1/**) 覆盖, Controller 线程自动有 TenantContext,
 * 这正是 start 能捕获租户快照的前提。
 */
@Slf4j
@RestController
@RequestMapping("/v1/workflow")
@RequiredArgsConstructor
public class WorkflowController {

    private final WorkflowEngine engine;
    private final WorkflowDefinitionMapper defMapper;
    private final WorkflowInstanceMapper instMapper;
    private final NodeInstanceMapper nodeInstMapper;
    private final HumanTaskMapper humanTaskMapper;
    private final EventStore eventStore;
    private final DslParser dslParser;

    /** 创建工作流定义 */
    @PostMapping("/definitions")
    public Result<Long> createDef(@RequestBody @Valid CreateDefRequest req) {
        dslParser.parse(req.getDsl());   // 校验 DSL(环/孤儿)
        WorkflowDefinition def = new WorkflowDefinition();
        def.setWorkflowCode(req.getWorkflowCode());
        def.setVersion(req.getVersion());
        def.setName(req.getName());
        def.setDsl(req.getDsl());
        def.setStatus(1);
        defMapper.insert(def);
        return Result.success(def.getId());
    }

    /** 启动实例(Controller 线程有 TenantContext, 快照在此捕获) */
    @PostMapping("/instances")
    public Result<Long> start(@RequestBody @Valid StartRequest req) {
        return engine.start(req);
    }

    /** 查实例状态 + 各节点状态 */
    @GetMapping("/instances/{id}")
    public Result<Map<String, Object>> status(@PathVariable Long id) {
        WorkflowInstance inst = instMapper.selectById(id);
        if (inst == null) throw new BizException(ErrorCode.WF_INSTANCE_NOT_FOUND);
        List<NodeInstance> nodes = nodeInstMapper.selectByInstance(id);
        Map<String, Object> data = new HashMap<>();
        data.put("instanceId", inst.getId());
        data.put("status", inst.getStatus());
        data.put("workflowCode", inst.getWorkflowCode());
        data.put("version", inst.getVersion());
        data.put("nodes", nodes);
        // 若 PAUSED, 附带待审批 humanTask
        if ("PAUSED".equals(inst.getStatus())) {
            HumanTask ht = humanTaskMapper.selectPendingByInstance(id);
            data.put("pendingHumanTask", ht);
        }
        return Result.success(data);
    }

    /** 人工审批 */
    @PostMapping("/human-tasks/{id}/decide")
    public Result<Void> decide(@PathVariable Long id, @RequestBody @Valid DecideRequest req) {
        HumanTask ht = humanTaskMapper.selectById(id);
        if (ht == null) throw new BizException(ErrorCode.WF_NOT_FOUND, "审批任务不存在");
        if (!"PENDING".equals(ht.getStatus())) {
            throw new BizException(ErrorCode.WF_NODE_FAILED, "审批任务已处理: " + ht.getStatus());
        }
        ht.setStatus(req.getDecision());   // APPROVED / REJECTED
        ht.setDecision(req.getDecision());
        ht.setDecidedAt(LocalDateTime.now());
        humanTaskMapper.updateById(ht);

        Long instanceId = ht.getInstanceId();
        if ("APPROVED".equals(req.getDecision())) {
            // 把 HUMAN 节点(处于 RUNNING)置 SUCCEEDED, 然后 resume 推进
            completeHumanNode(instanceId, ht.getNodeId());
            eventStore.append(instanceId, com.orca.workflow.event.EventType.HUMAN_APPROVED,
                    Map.of("nodeId", ht.getNodeId()));
            engine.resume(instanceId);
        } else {
            // REJECTED → 节点失败 → 触发 Saga 补偿 → FAILED
            eventStore.append(instanceId, com.orca.workflow.event.EventType.HUMAN_REJECTED,
                    Map.of("nodeId", ht.getNodeId()));
            engine.failAndCompensate(instanceId, ht.getNodeId(), "人工拒绝");
        }
        return Result.success(null);
    }

    /** 事件重放(验证事件溯源) */
    @GetMapping("/instances/{id}/events")
    public Result<List<WorkflowEvent>> events(@PathVariable Long id) {
        return Result.success(eventStore.replay(id));
    }

    private void completeHumanNode(Long instanceId, String nodeId) {
        NodeInstance ni = nodeInstMapper.selectByInstNode(instanceId, nodeId);
        if (ni != null && NodeStatus.RUNNING.name().equals(ni.getStatus())) {
            ni.setStatus(NodeStatus.SUCCEEDED.name());
            ni.setEndedAt(LocalDateTime.now());
            nodeInstMapper.updateById(ni);
        }
    }
}
