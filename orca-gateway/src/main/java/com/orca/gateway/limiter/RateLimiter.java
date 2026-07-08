package com.orca.gateway.limiter;

/**
 * 限流器接口。
 * 面试点: 限流算法选型(令牌桶/漏桶/滑动窗口), Redis+Lua 原子性, 分布式限流 vs 单机。
 */
public interface RateLimiter {

    /** 尝试消费 tokens 个令牌, 返回是否放行 + 剩余 */
    RateLimitResult tryConsume(long tenantId, LimitType type, int tokens, int capacity);

    /** 结算退回 tokens 个令牌(tpm 预扣多余时调用) */
    long refund(long tenantId, LimitType type, int tokens, int capacity);
}
