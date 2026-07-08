package com.orca.gateway.billing;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface BillingRecordMapper extends BaseMapper<BillingRecord> {

    /**
     * 幂等 upsert: ON DUPLICATE KEY UPDATE(靠 uk_tenant_period)。
     * 对账任务每日跑, 重跑覆盖更新。
     */
    @Insert("""
            INSERT INTO billing_record(tenant_id, period_date, total_tokens, total_calls, success_calls,
                billing_amount, redis_consumed, quota_consumed, diff_flag, settled)
            VALUES (#{b.tenantId}, #{b.periodDate}, #{b.totalTokens}, #{b.totalCalls}, #{b.successCalls},
                #{b.billingAmount}, #{b.redisConsumed}, #{b.quotaConsumed}, #{b.diffFlag}, #{b.settled})
            ON DUPLICATE KEY UPDATE
                total_tokens = VALUES(total_tokens),
                total_calls = VALUES(total_calls),
                success_calls = VALUES(success_calls),
                billing_amount = VALUES(billing_amount),
                redis_consumed = VALUES(redis_consumed),
                quota_consumed = VALUES(quota_consumed),
                diff_flag = VALUES(diff_flag)
            """)
    @Options(useGeneratedKeys = true, keyProperty = "b.id", keyColumn = "id")
    int upsertOnDuplicate(@Param("b") BillingRecord b);
}
