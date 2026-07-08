package com.orca.gateway.cache;

import com.orca.gateway.config.GatewayProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * PgVector 语义缓存实现。
 *
 * 面考点:
 *  1. 双路命中: prompt_hash 精确快路径(索引, 快) + 向量 ANN 语义路径(ivfflat, 覆盖相似问)
 *  2. 余弦距离 <=> : similarity = 1 - distance; sim ≥ threshold 命中
 *  3. 幂等写: ON CONFLICT (tenant_id, prompt_hash) DO UPDATE(PG 语法)
 *  4. 向量参数用 ?::vector 字符串 cast, 无需 pgvector Java 类型处理器
 */
@Slf4j
@Primary
@Service
public class PgVectorSemanticCacheService implements SemanticCacheService {

    private final EmbeddingProvider embeddingProvider;
    private final GatewayProperties gatewayProperties;
    private final JdbcTemplate pgJdbcTemplate;

    public PgVectorSemanticCacheService(EmbeddingProvider embeddingProvider,
                                        GatewayProperties gatewayProperties,
                                        @Qualifier("pgvectorJdbcTemplate") JdbcTemplate pgJdbcTemplate) {
        this.embeddingProvider = embeddingProvider;
        this.gatewayProperties = gatewayProperties;
        this.pgJdbcTemplate = pgJdbcTemplate;
    }

    @Override
    public Optional<CacheHit> tryGet(long tenantId, String model, String prompt) {
        if (!gatewayProperties.getCache().isEnabled()) {
            return Optional.empty();
        }
        String promptHash = sha256(normalize(prompt));

        // ① 精确快路径: hash 命中
        try {
            List<Map<String, Object>> exact = pgJdbcTemplate.queryForList(
                    "SELECT id, response_text, total_tokens FROM llm_cache " +
                            "WHERE tenant_id = ? AND model = ? AND prompt_hash = ? AND expires_at > now()",
                    tenantId, model, promptHash);
            if (!exact.isEmpty()) {
                Map<String, Object> row = exact.get(0);
                log.debug("[cache] 精确命中 tenant={} entry={}", tenantId, row.get("id"));
                return Optional.of(new CacheHit(
                        toLong(row.get("id")),
                        (String) row.get("response_text"),
                        toInt(row.get("total_tokens")),
                        false));
            }
        } catch (Exception e) {
            log.warn("[cache] 精确查询失败(降级跳过): {}", e.getMessage());
            return Optional.empty();
        }

        // ② 语义路径: 向量 ANN
        try {
            String vecLiteral = MockEmbeddingProvider.toVectorLiteral(embeddingProvider.embed(prompt));
            double threshold = gatewayProperties.getCache().getSimilarityThreshold();
            List<Map<String, Object>> rows = pgJdbcTemplate.queryForList(
                    "SELECT id, response_text, total_tokens, 1 - (embedding <=> ?::vector) AS sim " +
                            "FROM llm_cache " +
                            "WHERE tenant_id = ? AND model = ? AND expires_at > now() " +
                            "ORDER BY embedding <=> ?::vector LIMIT 1",
                    vecLiteral, tenantId, model, vecLiteral);
            if (!rows.isEmpty()) {
                Map<String, Object> row = rows.get(0);
                double sim = ((Number) row.get("sim")).doubleValue();
                if (sim >= threshold) {
                    log.debug("[cache] 语义命中 tenant={} sim={} entry={}", tenantId, sim, row.get("id"));
                    // 命中计数 +1(异步best-effort, 失败忽略)
                    try {
                        pgJdbcTemplate.update("UPDATE llm_cache SET hit_count = hit_count + 1 WHERE id = ?",
                                row.get("id"));
                    } catch (Exception ignored) {}
                    return Optional.of(new CacheHit(
                            toLong(row.get("id")),
                            (String) row.get("response_text"),
                            toInt(row.get("total_tokens")),
                            true));
                }
            }
        } catch (Exception e) {
            log.warn("[cache] 语义查询失败(降级跳过): {}", e.getMessage());
        }
        return Optional.empty();
    }

    @Override
    public void put(long tenantId, String model, String prompt, String response, int tokens) {
        if (!gatewayProperties.getCache().isEnabled()) {
            return;
        }
        try {
            String promptHash = sha256(normalize(prompt));
            String vecLiteral = MockEmbeddingProvider.toVectorLiteral(embeddingProvider.embed(prompt));
            int ttlHours = gatewayProperties.getCache().getTtlHours();
            // PG 幂等 upsert: ON CONFLICT (tenant_id, prompt_hash) DO UPDATE
            pgJdbcTemplate.update(
                    "INSERT INTO llm_cache(tenant_id, prompt_hash, prompt_text, embedding, response_text, model, total_tokens, expires_at) " +
                            "VALUES (?, ?, ?, ?::vector, ?, ?, ?, now() + interval '" + ttlHours + " hours') " +
                            "ON CONFLICT (tenant_id, prompt_hash) DO UPDATE SET " +
                            "response_text = EXCLUDED.response_text, embedding = EXCLUDED.embedding, " +
                            "total_tokens = EXCLUDED.total_tokens, expires_at = EXCLUDED.expires_at",
                    tenantId, promptHash, prompt, vecLiteral, response, model, tokens);
            log.debug("[cache] 写入 tenant={} model={} tokens={}", tenantId, model, tokens);
        } catch (Exception e) {
            // 写缓存失败不影响主流程
            log.warn("[cache] 写入失败(忽略): {}", e.getMessage());
        }
    }

    // ---- helpers ----

    private static String normalize(String s) {
        return s == null ? "" : s.trim().toLowerCase();
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    private static long toLong(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.longValue();
        return Long.parseLong(String.valueOf(o));
    }

    private static int toInt(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.intValue();
        return Integer.parseInt(String.valueOf(o));
    }
}
