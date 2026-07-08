package com.orca.gateway.limiter;

/**
 * 限流维度。
 * RPM = 每分钟请求数(每次消费 1 令牌)
 * TPM = 每分钟 token 数(预扣 maxTokens, 完成后退回多余)
 */
public enum LimitType {
    RPM,
    TPM
}
