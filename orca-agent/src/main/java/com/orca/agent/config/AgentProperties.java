package com.orca.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Agent 配置, 绑定 orca.agent.*。
 */
@Data
@Component
@ConfigurationProperties(prefix = "orca.agent")
public class AgentProperties {

    /** 最大循环步数 */
    private int maxSteps = 15;

    /** 最大 token 成本(累加 usage.totalTokens) */
    private long maxTokens = 50000;

    /** 单步超时(ms) */
    private long stepTimeoutMs = 30000;
}
