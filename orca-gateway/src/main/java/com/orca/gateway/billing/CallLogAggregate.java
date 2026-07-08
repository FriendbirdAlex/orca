package com.orca.gateway.billing;

import lombok.Data;

/**
 * call_log 按 tenant 聚合结果(对账用)。
 */
@Data
public class CallLogAggregate {
    private Long tenantId;
    private Long totalTokens;
    private Integer totalCalls;
    private Integer successCalls;
}
