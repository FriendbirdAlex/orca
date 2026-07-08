package com.orca.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

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

    @Data
    public static class Mock {
        /** Mock 流式切片数 */
        private int responseChunkCount = 20;
        /** 每个 chunk 间隔(ms), 模拟逐 token */
        private long chunkDelayMs = 80;
    }
}
