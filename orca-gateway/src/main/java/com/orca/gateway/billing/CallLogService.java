package com.orca.gateway.billing;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 调用流水服务。
 * P1: 同步 insert(简化, 便于验证)。
 * P2: 改 Kafka 异步记账, 解耦主流程 + 削峰; call_log 高写 → Kafka → ES。
 *
 * 面考点: 同步写 vs 异步写 trade-off; 记账失败不阻塞主流程(catch + 补偿)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CallLogService {

    private final CallLogMapper callLogMapper;

    public void record(CallLog logEntry) {
        try {
            callLogMapper.insert(logEntry);
        } catch (Exception e) {
            // 记账失败不影响主流程(限流/配额已扣, 业务已返回)
            // P2 用 Kafka + 重试保证最终一致
            log.error("[billing] call_log 写入失败 tenant={}", logEntry.getTenantId(), e);
        }
    }
}
