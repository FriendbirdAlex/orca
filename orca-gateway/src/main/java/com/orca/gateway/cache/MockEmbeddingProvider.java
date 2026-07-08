package com.orca.gateway.cache;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Mock Embedding: 词袋+哈希向量, 固定维度 384。
 * 跑通语义缓存向量检索链路; 生产换真实 embedding 实现此接口即可。
 *
 * 实现: 对文本分词后, 每个词哈希到 [0,dim) 某维累加, 归一化。
 * 相同/相似文本向量接近(余弦相似度高), 满足语义缓存演示。
 */
@Primary
@Component
public class MockEmbeddingProvider implements EmbeddingProvider {

    private final int dimension = 384;

    @Override
    public float[] embed(String text) {
        float[] vec = new float[dimension];
        if (text == null || text.isBlank()) {
            return vec;
        }
        // 简单分词: 按空格/标点切; 中文按字
        String[] tokens = text.toLowerCase().split("[\\s,，。.!！?？;；:：]+");
        for (String token : tokens) {
            if (token.isEmpty()) continue;
            int hash = Math.abs(token.hashCode());
            // 每个词影响 2 维(降低碰撞)
            vec[hash % dimension] += 1.0f;
            vec[(hash >> 8) % dimension] += 0.5f;
        }
        // L2 归一化(余弦相似度需要)
        double norm = 0;
        for (float v : vec) norm += v * v;
        norm = Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < dimension; i++) vec[i] = (float) (vec[i] / norm);
        }
        return vec;
    }

    @Override
    public int dimension() {
        return dimension;
    }

    /** 向量转 PgVector 字符串字面量 "[0.1,0.2,...]" (SQL 中用 ?::vector) */
    public static String toVectorLiteral(float[] vec) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vec[i]);
        }
        sb.append(']');
        return sb.toString();
    }
}
