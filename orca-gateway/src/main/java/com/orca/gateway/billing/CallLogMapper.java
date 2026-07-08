package com.orca.gateway.billing;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface CallLogMapper extends BaseMapper<CallLog> {

    /**
     * 幂等写入: ON DUPLICATE KEY UPDATE id=id (靠 uk_request_id), Kafka at-least-once 重投递不重复。
     * 字段全显式列出, 避免 MyBatis-Plus 自动映射与 LocalDateTime 处理差异。
     */
    @Insert("""
            INSERT INTO call_log(tenant_id, api_key_id, request_id, provider, model, stream,
                prompt_tokens, completion_tokens, total_tokens, latency_ms, status, error_code, trace_id, created_at)
            VALUES (#{l.tenantId}, #{l.apiKeyId}, #{l.requestId}, #{l.provider}, #{l.model}, #{l.stream},
                #{l.promptTokens}, #{l.completionTokens}, #{l.totalTokens}, #{l.latencyMs}, #{l.status}, #{l.errorCode}, #{l.traceId}, #{l.createdAt})
            ON DUPLICATE KEY UPDATE id = id
            """)
    @Options(useGeneratedKeys = true, keyProperty = "l.id", keyColumn = "id")
    int insertIgnoreOnDuplicate(@Param("l") CallLog l);

    /**
     * P2 对账: 按 tenant 聚合指定时段的 token/调用数。
     */
    @Select("""
            SELECT tenant_id AS tenantId,
                   COALESCE(SUM(total_tokens), 0) AS totalTokens,
                   COUNT(*) AS totalCalls,
                   SUM(CASE WHEN status = 'success' THEN 1 ELSE 0 END) AS successCalls
            FROM call_log
            WHERE created_at >= #{start} AND created_at < #{end}
            GROUP BY tenant_id
            """)
    List<CallLogAggregate> aggregateByTenant(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);
}
