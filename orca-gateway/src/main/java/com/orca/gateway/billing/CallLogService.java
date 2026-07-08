package com.orca.gateway.billing;

import com.orca.gateway.config.KafkaConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

/**
 * 调用流水服务。
 * P1: 同步 insert。
 * P2: 改 Kafka 异步记账 —— 主流程 send 即返回(削峰解耦), 由 CallLogConsumer 消费写 DB。
 *
 * 面考点:
 *  - 同步→异步: 主流程不阻塞, 高写流量由 Kafka 削峰
 *  - at-least-once + 幂等消费(requestId 唯一索引) 解决重复消费
 *  - send 失败仅 log, 不阻塞主流程(限流/配额已扣, 业务已返回); 极端丢失靠对账补偿
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CallLogService {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void record(CallLog logEntry) {
        try {
            // key=tenantId: 同租户同分区, 消费有序
            kafkaTemplate.send(KafkaConfig.TOPIC_CALL_LOG,
                    String.valueOf(logEntry.getTenantId()), logEntry);
        } catch (Exception e) {
            // 发送失败不阻塞主流程; 靠对账任务补偿(面试点: 最终一致 + 补偿)
            log.error("[billing] call_log 发送 Kafka 失败 tenant={}", logEntry.getTenantId(), e);
        }
    }
}
