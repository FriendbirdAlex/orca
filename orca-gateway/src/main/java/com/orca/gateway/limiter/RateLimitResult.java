package com.orca.gateway.limiter;

/**
 * 限流结果。allowed=false 表示被限流, remaining=剩余令牌。
 */
public record RateLimitResult(boolean allowed, long remaining) {

    public static RateLimitResult denied() {
        return new RateLimitResult(false, 0);
    }

    public static RateLimitResult ok(long remaining) {
        return new RateLimitResult(true, remaining);
    }
}
