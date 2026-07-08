package com.orca.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 网关配置, 绑定 orca.gateway.*。
 */
@Data
@Component
@ConfigurationProperties(prefix = "orca.gateway")
public class GatewayProperties {

    /** 默认 rpm(建租户未指定时) */
    private int defaultRpm = 60;

    /** 默认 tpm */
    private int defaultTpm = 100000;

    /** 默认每日配额 */
    private long defaultDailyQuota = 1_000_000L;

    private Mock mock = new Mock();

    /** P2 语义缓存配置 */
    private Cache cache = new Cache();

    /** P2 计费单价 */
    private Pricing pricing = new Pricing();

    @Data
    public static class Mock {
        /** Mock 流式切片数 */
        private int responseChunkCount = 20;
        /** 每个 chunk 间隔(ms), 模拟逐 token */
        private long chunkDelayMs = 80;
    }

    @Data
    public static class Cache {
        private boolean enabled = true;
        private int dimension = 384;
        /** 语义相似度阈值(余弦相似度), ≥ 此值命中 */
        private double similarityThreshold = 0.92;
        /** 缓存有效期(小时) */
        private int ttlHours = 24;
    }

    @Data
    public static class Pricing {
        /** 每 1k tokens 价格(元), 用于计费对账 */
        private BigDecimal perKTokens = new BigDecimal("0.002");
    }
}
