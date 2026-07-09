package com.orca.workflow.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orca.workflow.event.entity.WorkflowEvent;
import com.orca.workflow.event.mapper.WorkflowEventMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 事件存储(事件溯源)。
 *
 * 面考点:
 *  1. append-only: 事件只追加不修改, 真相源; 状态表(node_instance)是物化视图, 丢失可重放重建
 *  2. REQUIRES_NEW 独立事务: 即使主流程(如节点执行事务)回滚, 事件也落库, 保证不丢(审计/排障关键)
 *  3. seq 顺序: 应用层 MAX(seq)+1 + uk_inst_seq 唯一约束兜底, 防并发乱序
 *  4. 可选发 Kafka: 供审计/下游订阅, 失败仅 warn 不阻断主流程
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventStore {

    public static final String TOPIC = "orca-workflow-event";

    private final WorkflowEventMapper eventMapper;
    private final ObjectMapper objectMapper;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    /** append 事件, REQUIRES_NEW 保证主流程回滚也不丢事件 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void append(Long instanceId, EventType type, Map<String, Object> payload) {
        try {
            Integer maxSeq = eventMapper.selectMaxSeq(instanceId);
            WorkflowEvent e = new WorkflowEvent();
            e.setInstanceId(instanceId);
            e.setSeq(maxSeq == null ? 1 : maxSeq + 1);
            e.setEventType(type.name());
            e.setPayload(objectMapper.writeValueAsString(payload == null ? Map.of() : payload));
            eventMapper.insert(e);   // uk_inst_seq 兜底: 并发冲突时抛异常(可重试)

            // best-effort 发 Kafka(审计/下游), 失败仅 warn
            try {
                kafkaTemplate.send(TOPIC, String.valueOf(instanceId), e);
            } catch (Exception ex) {
                log.warn("[event] Kafka 发送失败 instance={} type={}", instanceId, type, ex);
            }
        } catch (Exception ex) {
            log.error("[event] append 失败 instance={} type={}", instanceId, type, ex);
            // 事件丢失只 log, 不抛(避免拖垮主流程; 面试点: 可加重试/本地表补偿)
        }
    }

    /** 重放: 按实例查全部事件(ORDER BY seq), 重建最终状态 */
    public List<WorkflowEvent> replay(Long instanceId) {
        return eventMapper.selectByInstance(instanceId);
    }
}
