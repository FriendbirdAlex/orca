package com.orca.gateway.provider;

/**
 * token 估算器。
 * 面试点: 真实模型 token 数需 tokenizer(如 tiktoken), 网关侧用粗估(chars/4)做预扣上界,
 *        完成后用 Provider 返回的真实 usage 结算退回。预扣偏大只浪费预留额度, 不影响正确性。
 */
public interface TokenEstimator {

    int estimate(String text);
}
