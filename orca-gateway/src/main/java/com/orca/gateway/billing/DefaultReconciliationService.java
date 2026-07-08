package com.orca.gateway.billing;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.orca.gateway.config.GatewayProperties;
import com.orca.gateway.quota.QuotaManager;
import com.orca.gateway.quota.mapper.TenantQuotaMapper;
import com.orca.gateway.quota.model.TenantQuota;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 计费对账默认实现: @Scheduled 每日 2am 跑。
 *
 * 面考点:
 *  1. 三方对账: call_log 聚合(实际用量) vs tenant_quota.consumed_tokens(配额持久化) vs Redis 热值
 *     - 主对比: call_log vs tenant_quota(Redis 侧以 tenant_quota 为代表, 因 Redis key TTL 25h, 2am 昨日 key 可能已过期)
 *  2. 计费金额 = totalTokens / 1000 * perKTokens
 *  3. 差异超阈(默认 1% 或 100 token) → diff_flag=1 + 告警日志
 *  4. 幂等 upsert billing_record(uk_tenant_period), 重跑覆盖
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultReconciliationService implements ReconciliationService {

    private final CallLogMapper callLogMapper;
    private final BillingRecordMapper billingRecordMapper;
    private final TenantQuotaMapper tenantQuotaMapper;
    private final QuotaManager quotaManager;
    private final GatewayProperties gatewayProperties;

    /** 差异告警阈值(token 绝对值) */
    private static final long DIFF_THRESHOLD_TOKENS = 100;

    /** 每日凌晨 2:10 跑(对账前一天, 留 buffer 让 Kafka 消费完) */
    @Scheduled(cron = "0 10 2 * * ?", zone = "Asia/Shanghai")
    public void scheduledReconcile() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        log.info("[reconcile] 定时对账触发 period={}", yesterday);
        reconcile(yesterday);
    }

    @Override
    public ReconciliationResult reconcile(LocalDate period) {
        return doReconcile(period);
    }

    @Override
    public ReconciliationResult reconcileNow(LocalDate period) {
        return doReconcile(period);
    }

    private ReconciliationResult doReconcile(LocalDate period) {
        LocalDateTime start = period.atStartOfDay();
        LocalDateTime end = period.plusDays(1).atStartOfDay();
        BigDecimal perK = gatewayProperties.getPricing().getPerKTokens();

        // ① call_log 按 tenant 聚合
        List<CallLogAggregate> aggregates = callLogMapper.aggregateByTenant(start, end);
        log.info("[reconcile] period={} 聚合租户数={}", period, aggregates.size());

        List<TenantReport> reports = new ArrayList<>();
        int alertCount = 0;

        for (CallLogAggregate agg : aggregates) {
            long tenantId = agg.getTenantId();
            long dbTokens = agg.getTotalTokens() == null ? 0 : agg.getTotalTokens();

            // ② tenant_quota 持久化值(Redis 侧代表)
            long quotaConsumed = queryQuotaConsumed(tenantId, period);

            // ③ best-effort Redis 热值(可能过期)
            long redisConsumed = safeRedisConsumed(tenantId);

            // ④ 计费金额
            BigDecimal amount = BigDecimal.valueOf(dbTokens)
                    .divide(BigDecimal.valueOf(1000), 4, RoundingMode.HALF_UP)
                    .multiply(perK);

            // ⑤ 差异判断
            boolean diff = Math.abs(dbTokens - quotaConsumed) > DIFF_THRESHOLD_TOKENS;
            if (diff) {
                alertCount++;
                log.warn("[reconcile] 差异告警 tenant={} dbTokens={} quotaConsumed={} diff={}",
                        tenantId, dbTokens, quotaConsumed, dbTokens - quotaConsumed);
            }

            // ⑥ upsert billing_record
            BillingRecord rec = new BillingRecord();
            rec.setTenantId(tenantId);
            rec.setPeriodDate(period);
            rec.setTotalTokens(dbTokens);
            rec.setTotalCalls(agg.getTotalCalls());
            rec.setSuccessCalls(agg.getSuccessCalls());
            rec.setBillingAmount(amount);
            rec.setRedisConsumed(redisConsumed);
            rec.setQuotaConsumed(quotaConsumed);
            rec.setDiffFlag(diff ? 1 : 0);
            rec.setSettled(0);
            billingRecordMapper.upsertOnDuplicate(rec);

            reports.add(new TenantReport(tenantId, dbTokens, quotaConsumed, redisConsumed,
                    agg.getTotalCalls(), agg.getSuccessCalls(), amount, diff));
        }

        log.info("[reconcile] period={} 完成, 告警数={}", period, alertCount);
        return new ReconciliationResult(period, reports, alertCount);
    }

    private long queryQuotaConsumed(long tenantId, LocalDate period) {
        TenantQuota q = tenantQuotaMapper.selectOne(new LambdaQueryWrapper<TenantQuota>()
                .eq(TenantQuota::getTenantId, tenantId)
                .eq(TenantQuota::getQuotaDate, period));
        return q == null || q.getConsumedTokens() == null ? 0 : q.getConsumedTokens();
    }

    /** Redis 热值查询, 过期/异常返回 -1(表示不可用) */
    private long safeRedisConsumed(long tenantId) {
        try {
            return quotaManager.consumed(tenantId);
        } catch (Exception e) {
            log.debug("[reconcile] Redis consumed 查询失败 tenant={}: {}", tenantId, e.getMessage());
            return -1;
        }
    }
}
