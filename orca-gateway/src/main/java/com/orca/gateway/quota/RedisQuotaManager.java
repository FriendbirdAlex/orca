package com.orca.gateway.quota;

import com.orca.gateway.quota.mapper.TenantQuotaMapper;
import com.orca.gateway.quota.model.TenantQuota;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Redis + Lua 配额管理实现。
 *
 * 面试点(最终一致性):
 *  1. reserve/refund 走 Redis Lua 原子操作(热路径, 高并发)
 *  2. refund 后同步 upsert tenant_quota 表, DB 作为源记录(对账/重建依据)
 *  3. Redis 故障丢数据时, 按 tenant_quota.consumed_tokens 重建 Redis 计数
 *  4. key 按天, TTL 90000s(>24h), 跨天自动新桶, 无需手动清理
 *
 * 注意: upsert 用 INSERT ON DUPLICATE KEY UPDATE 保证幂等(UK tenant+date)。
 */
@Slf4j
@Primary
@Component
public class RedisQuotaManager implements QuotaManager {

    private static final String KEY_PREFIX = "orca:quota:";
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final long TTL_SECONDS = 90000L;   // >24h

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<List> quotaPredeductScript;
    private final DefaultRedisScript<Long> quotaRefundScript;
    private final TenantQuotaMapper tenantQuotaMapper;

    public RedisQuotaManager(StringRedisTemplate redis,
                             @Qualifier("quotaPredeductScript") DefaultRedisScript<List> quotaPredeductScript,
                             @Qualifier("quotaRefundScript") DefaultRedisScript<Long> quotaRefundScript,
                             TenantQuotaMapper tenantQuotaMapper) {
        this.redis = redis;
        this.quotaPredeductScript = quotaPredeductScript;
        this.quotaRefundScript = quotaRefundScript;
        this.tenantQuotaMapper = tenantQuotaMapper;
    }

    @Override
    public QuotaResult reserve(long tenantId, int tokens, long dailyLimit) {
        String key = buildKey(tenantId);
        List<?> ret = redis.execute(quotaPredeductScript,
                List.of(key),
                String.valueOf(dailyLimit),
                String.valueOf(tokens),
                String.valueOf(TTL_SECONDS));
        if (ret == null || ret.size() < 2) {
            log.warn("[quota] lua 返回异常 tenant={} ret={}", tenantId, ret);
            return QuotaResult.denied();
        }
        long allowed = toLong(ret.get(0));
        long remaining = toLong(ret.get(1));
        return allowed == 1 ? QuotaResult.ok(remaining) : QuotaResult.denied();
    }

    @Override
    public long refund(long tenantId, int tokens, long dailyLimit) {
        if (tokens <= 0) {
            // 即使不退回, 也同步一次 DB(保证源记录存在)
            persist(tenantId, consumed(tenantId), dailyLimit);
            return consumed(tenantId);
        }
        String key = buildKey(tenantId);
        Long ret = redis.execute(quotaRefundScript, List.of(key), String.valueOf(tokens));
        long consumedNow = ret == null ? 0 : ret;
        // 最终一致: 同步落 DB 源记录
        persist(tenantId, consumedNow, dailyLimit);
        return consumedNow;
    }

    @Override
    public long consumed(long tenantId) {
        String key = buildKey(tenantId);
        // 用 HSET 存 consumed, 读 hash 字段
        Object h = redis.opsForHash().get(key, "consumed");
        if (h == null) return 0;
        return toLong(h);
    }

    private void persist(long tenantId, long consumed, long dailyLimit) {
        try {
            TenantQuota q = new TenantQuota();
            q.setTenantId(tenantId);
            q.setQuotaDate(LocalDate.now());
            q.setDailyLimit(dailyLimit);
            q.setConsumedTokens(consumed);
            tenantQuotaMapper.upsertOnDuplicate(q);
        } catch (Exception e) {
            // DB 写失败不阻塞主流程(已扣 Redis), 靠对账任务补偿。面试点: 最终一致 + 补偿。
            log.error("[quota] persist tenant_quota 失败 tenant={} consumed={}", tenantId, consumed, e);
        }
    }

    private String buildKey(long tenantId) {
        return KEY_PREFIX + tenantId + ":" + LocalDate.now().format(DATE_FMT);
    }

    private long toLong(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.longValue();
        return Long.parseLong(String.valueOf(o));
    }
}
