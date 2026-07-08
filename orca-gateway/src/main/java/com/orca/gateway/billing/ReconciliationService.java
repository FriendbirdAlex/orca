package com.orca.gateway.billing;

import java.time.LocalDate;
import java.util.List;

/**
 * 计费对账服务。
 * 面考点: 三方数据源对账(call_log 聚合 / tenant_quota 持久化 / Redis 热值), 最终一致 + 差异告警。
 */
public interface ReconciliationService {

    /** 对账指定日期 */
    ReconciliationResult reconcile(LocalDate period);

    /** 手动触发(测试/接口用) */
    ReconciliationResult reconcileNow(LocalDate period);

    record TenantReport(
            long tenantId,
            long dbTokens,         // call_log 聚合
            long quotaConsumed,    // tenant_quota 持久化值
            long redisConsumed,    // best-effort Redis 值(-1=过期/不可用)
            int totalCalls,
            int successCalls,
            java.math.BigDecimal billingAmount,
            boolean diff           // |dbTokens - quotaConsumed| 超阈
    ) {
    }

    record ReconciliationResult(
            LocalDate period,
            List<TenantReport> reports,
            int alertCount
    ) {
    }
}
