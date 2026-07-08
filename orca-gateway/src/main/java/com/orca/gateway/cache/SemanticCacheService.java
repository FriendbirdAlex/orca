package com.orca.gateway.cache;

import java.util.Optional;

/**
 * 语义缓存服务。
 *
 * 面考点:
 *  - 双路命中: prompt_hash 精确快路径(命中率高、快) + 向量 ANN 语义模糊路径(覆盖相似问)
 *  - 缓存命中不扣配额/不调 Provider, 大幅降本提速
 *  - 一致性: TTL 过期 + ON CONFLICT 幂等写
 */
public interface SemanticCacheService {

    /** 查缓存: 先精确 hash, 再向量 ANN */
    Optional<CacheHit> tryGet(long tenantId, String model, String prompt);

    /** 写缓存(Provider 返回后调用) */
    void put(long tenantId, String model, String prompt, String response, int tokens);
}
