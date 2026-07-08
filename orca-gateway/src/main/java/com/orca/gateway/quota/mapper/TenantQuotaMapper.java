package com.orca.gateway.quota.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.orca.gateway.quota.model.TenantQuota;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;

/**
 * 租户每日配额 Mapper。
 * upsertOnDuplicate: INSERT ON DUPLICATE KEY UPDATE 保证幂等(UK tenant+date)。
 */
@Mapper
public interface TenantQuotaMapper extends BaseMapper<TenantQuota> {

    /**
     * 插入或更新当日 consumed_tokens(以传入值为准, 反映 Redis 当前值)。
     * 面试点: ON DUPLICATE KEY UPDATE 是 MySQL 原生 upsert, 靠唯一键 UK(tenant_id, quota_date)。
     */
    @Insert("""
            INSERT INTO tenant_quota(tenant_id, quota_date, daily_limit, consumed_tokens)
            VALUES (#{q.tenantId}, #{q.quotaDate}, #{q.dailyLimit}, #{q.consumedTokens})
            ON DUPLICATE KEY UPDATE
              consumed_tokens = #{q.consumedTokens},
              daily_limit = #{q.dailyLimit}
            """)
    @Options(useGeneratedKeys = true, keyProperty = "q.id", keyColumn = "id")
    int upsertOnDuplicate(@Param("q") TenantQuota q);
}
