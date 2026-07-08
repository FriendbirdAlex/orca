package com.orca.gateway.cache;

/**
 * Embedding 提供者: 将文本转向量。
 * 面试点: 接口抽象, 生产换真实 embedding(OpenAI/智谱)只需新增实现, 网关零改动(OCP)。
 */
public interface EmbeddingProvider {

    /** 文本转向量 */
    float[] embed(String text);

    /** 向量维度 */
    int dimension();
}
