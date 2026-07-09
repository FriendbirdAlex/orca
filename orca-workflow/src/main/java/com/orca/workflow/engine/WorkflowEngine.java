package com.orca.workflow.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orca.common.context.TenantContext;
import com.orca.common.context.TenantInfo;
import com.orca.common.exception.BizException;
import com.orca.common.exception.ErrorCode;
import com.orca.common.result.Result;
import com.orca.workflow.controller.dto.StartRequest;
import com.orca.workflow.dsl.entity.WorkflowDefinition;
import com.orca.workflow.dsl.WorkflowDsl;
import com.orca.workflow.dsl.mapper.WorkflowDefinitionMapper;
import com.orca.workflow.engine.entity.NodeInstance;
import com.orca.workflow.engine.entity.WorkflowInstance;
import com.orca.workflow.engine.mapper.NodeInstanceMapper;
import com.orca.workflow.engine.mapper.WorkflowInstanceMapper;
import com.orca.workflow.event.EventStore;
import com.orca.workflow.event.EventType;
import com.orca.workflow.state.InstanceStatus;
import com.orca.workflow.state.NodeStatus;
import com.orca.workflow.state.StateMachine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 工作流引擎: start / resume / advance 三入口。
 *
 * start: 幂等(biz_id) + 取定义解析 DSL + 捕获租户快照 + 建实例 RUNNING + STARTED 事件 + initPending + advance
 * resume: 人工审批后 PAUSED→RUNNING + RESUMED 事件 + advance
 * advance: 找就绪节点执行 → 更新状态 → 循环到终态/暂停
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowEngine {

    private final WorkflowDefinitionMapper defMapper;
    private final WorkflowInstanceMapper instMapper;
    private final NodeInstanceMapper nodeInstMapper;
    private final DagExecutor dagExecutor;
    private final EventStore eventStore;
    private final StateMachine stateMachine;
    private final ObjectMapper objectMapper;
    private final com.orca.workflow.compensation.SagaCompensator sagaCompensator;

    /**
     * 启动工作流实例(幂等: biz_id 已存在返回已有实例)。
     * 不加 @Transactional: 建实例/事件/initPending 各自独立(EventStore.append 自带 REQUIRES_NEW),
     * 避免长事务包裹节点执行(advance 里 LLM 调用慢/可能阻塞)。advance 异步推进, start 秒回。
     */
    public Result<Long> start(StartRequest req) {
        // 1. 幂等
        WorkflowInstance exist = instMapper.selectByBizId(req.getBizId());
        if (exist != null) {
            log.info("[engine] 幂等命中 bizId={} instance={}", req.getBizId(), exist.getId());
            return Result.success(exist.getId());
        }
        // 2. 取定义 + 解析 DSL
        WorkflowDefinition def = defMapper.selectByCodeVersion(req.getWorkflowCode(), req.getVersion());
        if (def == null) throw new BizException(ErrorCode.WF_NOT_FOUND);
        WorkflowDsl dsl = dagExecutor.parseAndCache(def.getDsl());

        // 3. 捕获租户快照(Controller 线程有 TenantContext)
        TenantInfo t = TenantContext.require();
        String snapshot;
        try {
            snapshot = objectMapper.writeValueAsString(t);
        } catch (Exception e) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "租户快照序列化失败");
        }

        // 4. 建实例
        WorkflowInstance inst = new WorkflowInstance();
        inst.setWorkflowCode(req.getWorkflowCode());
        inst.setVersion(req.getVersion());
        inst.setStatus(InstanceStatus.RUNNING.name());
        inst.setTriggerType("API");
        inst.setBizId(req.getBizId());
        inst.setTenantId(t.tenantId());
        inst.setTenantSnapshot(snapshot);
        inst.setInput(toJson(req.getInput()));
        instMapper.insert(inst);

        // 5. STARTED 事件 + 初始化 PENDING 节点
        eventStore.append(inst.getId(), EventType.STARTED, Map.of("input", req.getInput() == null ? Map.of() : req.getInput()));
        dagExecutor.initPendingNodes(inst.getId(), dsl);
        log.info("[engine] 启动实例 instance={} workflow={}:{} tenant={}", inst.getId(), req.getWorkflowCode(), req.getVersion(), t.tenantId());

        // 6. 不在此 advance: 统一由调度器(带分布式锁)推进, 避免 start 异步 advance 与调度器并发重复执行节点
        //    调度器 scan-interval-ms(3s) 内会扫到 RUNNING 实例并推进
        log.info("[engine] 实例已创建, 等待调度器推进 instance={}", inst.getId());
        return Result.success(inst.getId());
    }

    /** 推进实例: 找就绪节点执行 */
    public void advance(Long instanceId) {
        WorkflowInstance inst = instMapper.selectById(instanceId);
        if (inst == null) throw new BizException(ErrorCode.WF_INSTANCE_NOT_FOUND);
        if (!InstanceStatus.RUNNING.name().equals(inst.getStatus())) {
            return;   // PAUSED/SUCCEEDED/FAILED 不推进
        }
        WorkflowDefinition def = defMapper.selectByCodeVersion(inst.getWorkflowCode(), inst.getVersion());
        WorkflowDsl dsl = dagExecutor.parseAndCache(def.getDsl());
        InstanceContext ctx = loadContext(inst);
        dagExecutor.executeBatch(inst, dsl, ctx);
        // 持久化 context(节点输出快照)
        persistContext(inst, ctx);
    }

    /** 恢复: 人工审批 APPROVED 后 PAUSED→RUNNING + 推进 */
    @Transactional
    public void resume(Long instanceId) {
        WorkflowInstance inst = instMapper.selectById(instanceId);
        if (inst == null) throw new BizException(ErrorCode.WF_INSTANCE_NOT_FOUND);
        stateMachine.checkInstance(InstanceStatus.valueOf(inst.getStatus()), InstanceStatus.RUNNING);
        inst.setStatus(InstanceStatus.RUNNING.name());
        instMapper.updateById(inst);
        eventStore.append(instanceId, EventType.RESUMED, Map.of());
        log.info("[engine] 恢复实例 instance={}", instanceId);
        advance(instanceId);
    }

    /**
     * 节点失败 + Saga 补偿(人工拒绝/审批超时用)。
     * 把指定节点置 FAILED + 发 NODE_FAILED 事件 + 触发 Saga 逆序补偿 → 实例 FAILED。
     */
    @Transactional
    public void failAndCompensate(Long instanceId, String nodeId, String reason) {
        WorkflowInstance inst = instMapper.selectById(instanceId);
        if (inst == null) throw new BizException(ErrorCode.WF_INSTANCE_NOT_FOUND);
        // 若 PAUSED(人工暂停中), 先转 RUNNING 再补偿
        if (InstanceStatus.PAUSED.name().equals(inst.getStatus())) {
            inst.setStatus(InstanceStatus.RUNNING.name());
            instMapper.updateById(inst);
        }
        // 节点置 FAILED
        NodeInstance ni = nodeInstMapper.selectByInstNode(instanceId, nodeId);
        if (ni != null && !NodeStatus.FAILED.name().equals(ni.getStatus())) {
            ni.setStatus(NodeStatus.FAILED.name());
            ni.setError(reason);
            ni.setEndedAt(LocalDateTime.now());
            nodeInstMapper.updateById(ni);
        }
        eventStore.append(instanceId, EventType.NODE_FAILED, Map.of("nodeId", nodeId, "error", reason));
        WorkflowDefinition def = defMapper.selectByCodeVersion(inst.getWorkflowCode(), inst.getVersion());
        WorkflowDsl dsl = dagExecutor.parseAndCache(def.getDsl());
        InstanceContext ctx = loadContext(inst);
        sagaCompensator.compensate(instanceId, dsl, ctx);
    }

    private InstanceContext loadContext(WorkflowInstance inst) {
        InstanceContext ctx = new InstanceContext();
        ctx.setInstanceId(inst.getId());
        ctx.setWorkflowCode(inst.getWorkflowCode());
        ctx.setVersion(inst.getVersion());
        ctx.setTenantSnapshot(inst.getTenantSnapshot());
        if (inst.getInput() != null) {
            try { ctx.setInput(objectMapper.readValue(inst.getInput(), Map.class)); } catch (Exception ignored) {}
        }
        return ctx;
    }

    private void persistContext(WorkflowInstance inst, InstanceContext ctx) {
        try {
            // 只更新 context 字段, 不覆盖 status(HUMAN 节点可能已把实例置 PAUSED, 全量 updateById 会覆盖)
            WorkflowInstance update = new WorkflowInstance();
            update.setId(inst.getId());
            update.setContext(objectMapper.writeValueAsString(ctx.getNodeOutputs()));
            instMapper.updateById(update);
        } catch (Exception e) {
            log.warn("[engine] 持久化 context 失败 instance={}", inst.getId(), e);
        }
    }

    private String toJson(Object obj) {
        try { return objectMapper.writeValueAsString(obj == null ? Map.of() : obj); }
        catch (Exception e) { return "{}"; }
    }
}
