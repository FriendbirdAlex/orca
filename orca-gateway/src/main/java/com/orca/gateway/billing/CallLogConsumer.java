package com.orca.gateway.billing;

import com.orca.gateway.config.KafkaConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * call_log Kafka 消费者: 异步写 DB。
 *
 * 面考点:
 *  1. 幂等消费: insertIgnoreOnDuplicate 靠 requestId 唯一索引, 重投递(Kafka at-least-once)不重复写
 *  2. ack-mode=record: 容器按消息自动确认 offset, 消费抛异常由 DefaultErrorHandler 重试 → DLT
 *  3. 削峰: 生产端高写流量先入 Kafka, 消费端按 concurrency=3 平滑写 DB
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CallLogConsumer {

    private final CallLogMapper callLogMapper;

    @KafkaListener(
            topics = KafkaConfig.TOPIC_CALL_LOG,
            groupId = KafkaConfig.GROUP_CALL_LOG_WRITER,
            containerFactory = "callLogListenerContainerFactory"
    )
    public void onMessage(CallLog logEntry) {
        // 幂等写: ON DUPLICATE KEY UPDATE id=id (靠 uk_request_id), 重投递无副作用
        // 抛异常 → DefaultErrorHandler 重试, 耗尽转 DLT; ack 由容器 RECORD 模式自动管理
        callLogMapper.insertIgnoreOnDuplicate(logEntry);
        log.debug("[billing-consumer] 写入 call_log tenant={} requestId={}",
                logEntry.getTenantId(), logEntry.getRequestId());
    }
}
