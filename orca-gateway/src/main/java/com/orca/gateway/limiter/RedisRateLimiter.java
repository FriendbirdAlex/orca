package com.orca.gateway.limiter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Redis + Lua 令牌桶限流实现。
 *
 * 面试点:
 *  1. 为什么 Redis+Lua 保证原子? Redis 单线程处理命令, Lua 脚本作为整体执行不可被其它命令打断,
 *     "查令牌+计算+扣减"无竞态。对比"先 GET 再 SET"在并发下会读到旧值导致超卖。
 *  2. Key 设计: orca:rl:{RPM|TPM}:{tenantId}, 每租户每维度独立桶。
 *  3. refillRate = capacity/60 (每秒补充), 用 Redis TIME 服务端时间。
 *  4. 容量由调用方传(=租户 rpm/tpm 配置), 支持每租户不同额度。
 */
@Slf4j
@Primary
@Component
public class RedisRateLimiter implements RateLimiter {

    private static final String KEY_PREFIX = "orca:rl:";

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<List> tokenBucketScript;
    private final DefaultRedisScript<Long> tokenRefundScript;

    public RedisRateLimiter(StringRedisTemplate redis,
                            @Qualifier("tokenBucketScript") DefaultRedisScript<List> tokenBucketScript,
                            @Qualifier("tokenRefundScript") DefaultRedisScript<Long> tokenRefundScript) {
        this.redis = redis;
        this.tokenBucketScript = tokenBucketScript;
        this.tokenRefundScript = tokenRefundScript;
    }

    @Override
    public RateLimitResult tryConsume(long tenantId, LimitType type, int tokens, int capacity) {
        String key = buildKey(tenantId, type);
        double refillRate = capacity / 60.0;   // 每秒补充
        List<?> ret = redis.execute(tokenBucketScript,
                List.of(key),
                String.valueOf(capacity),
                String.valueOf(refillRate),
                String.valueOf(tokens));
        if (ret == null || ret.size() < 2) {
            log.warn("[limiter] lua 返回异常 tenant={} type={} ret={}", tenantId, type, ret);
            return RateLimitResult.denied();
        }
        long allowed = toLong(ret.get(0));
        long remaining = toLong(ret.get(1));
        return allowed == 1 ? RateLimitResult.ok(remaining) : RateLimitResult.denied();
    }

    @Override
    public long refund(long tenantId, LimitType type, int tokens, int capacity) {
        if (tokens <= 0) return 0;
        String key = buildKey(tenantId, type);
        Long ret = redis.execute(tokenRefundScript,
                List.of(key),
                String.valueOf(capacity),
                String.valueOf(tokens));
        return ret == null ? 0 : ret;
    }

    private String buildKey(long tenantId, LimitType type) {
        return KEY_PREFIX + type.name() + ":" + tenantId;
    }

    @SuppressWarnings("unchecked")
    private long toLong(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.longValue();
        return Long.parseLong(String.valueOf(o));
    }
}
