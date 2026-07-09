package com.orca.workflow.scheduler;

import com.orca.workflow.config.WorkflowProperties;
import com.orca.workflow.engine.WorkflowEngine;
import com.orca.workflow.engine.entity.WorkflowInstance;
import com.orca.workflow.engine.mapper.WorkflowInstanceMapper;
import com.orca.workflow.event.EventStore;
import com.orca.workflow.event.EventType;
import com.orca.workflow.scheduler.entity.HumanTask;
import com.orca.workflow.scheduler.mapper.HumanTaskMapper;
import com.orca.workflow.state.InstanceStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 工作流调度器: 扫描 RUNNING 实例推进 + 人工审批超时扫描。
 *
 * 面考点(防重/防漏):
 *  - 防重: 实例级 Redisson 锁(非全局, 多实例并行度高) + 二次状态校验 + uk_inst_node 幂等
 *  - 防漏: fixedDelay(非 fixedRate, 防堆积) + 失败 next_schedule_at 退避(防坏实例空转抢占锁)
 *  - tryLock 不阻塞扫描: 抢不到说明别的实例在推进, 跳过
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowScheduler {

    private final WorkflowInstanceMapper instMapper;
    private final HumanTaskMapper humanTaskMapper;
    private final WorkflowEngine engine;
    private final EventStore eventStore;
    private final RedissonClient redisson;
    private final WorkflowProperties props;

    /** 扫描 RUNNING 实例推进, fixedDelay 防上一轮未完成堆积 */
    @Scheduled(fixedDelayString = "${orca.workflow.scan-interval-ms:3000}")
    public void scanInstances() {
        List<WorkflowInstance> ready;
        try {
            ready = instMapper.selectScheduledReady(LocalDateTime.now(), props.getSchedulerBatchSize());
        } catch (Exception e) {
            log.error("[scheduler] 查询可调度实例失败", e);
            return;
        }
        for (WorkflowInstance inst : ready) {
            String lockKey = "orca:wf:lock:" + inst.getId();
            RLock lock = redisson.getLock(lockKey);
            boolean locked = false;
            try {
                locked = lock.tryLock(props.getLockWaitMs(), TimeUnit.MILLISECONDS);
                if (!locked) continue;   // 别的实例正在推进, 跳过
                // 二次校验(锁期间可能已被推进)
                WorkflowInstance fresh = instMapper.selectById(inst.getId());
                if (!InstanceStatus.RUNNING.name().equals(fresh.getStatus())) continue;
                if (fresh.getNextScheduleAt() != null && fresh.getNextScheduleAt().isAfter(LocalDateTime.now())) continue;
                engine.advance(inst.getId());
            } catch (Exception e) {
                log.error("[scheduler] 推进失败 instance={}", inst.getId(), e);
                // 退避: 防坏实例无限快速重试
                instMapper.updateNextScheduleAt(inst.getId(), LocalDateTime.now().plusNanos(props.getBackoffMs() * 1_000_000L));
            } finally {
                if (locked && lock.isHeldByCurrentThread()) lock.unlock();
            }
        }
    }

    /** 人工审批超时扫描: PENDING & timeout_at<=now → TIMEOUT → 节点失败 → 实例 FAILED */
    @Scheduled(fixedDelayString = "${orca.workflow.human-task-scan-interval-ms:10000}")
    public void scanHumanTaskTimeout() {
        List<HumanTask> expired;
        try {
            expired = humanTaskMapper.selectExpired(LocalDateTime.now());
        } catch (Exception e) {
            log.error("[scheduler] 查询超时审批任务失败", e);
            return;
        }
        for (HumanTask ht : expired) {
            RLock lock = redisson.getLock("orca:wf:human:" + ht.getId());
            boolean locked = false;
            try {
                locked = lock.tryLock(0, TimeUnit.MILLISECONDS);
                if (!locked) continue;
                ht.setStatus("TIMEOUT");
                ht.setDecidedAt(LocalDateTime.now());
                humanTaskMapper.updateById(ht);
                eventStore.append(ht.getInstanceId(), EventType.HUMAN_TASK_TIMEOUT, Map.of("nodeId", ht.getNodeId()));
                log.info("[scheduler] 审批超时 humanTask={} instance={}", ht.getId(), ht.getInstanceId());
                // 超时 = 节点失败 → 触发 Saga → FAILED
                engine.failAndCompensate(ht.getInstanceId(), ht.getNodeId(), "审批超时");
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.warn("[scheduler] 超时审批加锁中断 humanTask={}", ht.getId());
            } catch (Exception e) {
                log.error("[scheduler] 处理超时审批失败 humanTask={}", ht.getId(), e);
            } finally {
                if (locked && lock.isHeldByCurrentThread()) lock.unlock();
            }
        }
    }
}
