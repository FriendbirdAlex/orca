package com.orca.gateway.cache;

/**
 * 语义缓存命中结果。
 */
public record CacheHit(
        long entryId,
        String responseText,
        int totalTokens,
        boolean semantic   // true=语义命中, false=精确命中
) {
}
