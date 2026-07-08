package com.orca.gateway.quota;

/**
 * 配额预扣结果。allowed=false 表示配额不足, remaining=今日剩余配额。
 */
public record QuotaResult(boolean allowed, long remaining) {

    public static QuotaResult denied() {
        return new QuotaResult(false, 0);
    }

    public static QuotaResult ok(long remaining) {
        return new QuotaResult(true, remaining);
    }
}
