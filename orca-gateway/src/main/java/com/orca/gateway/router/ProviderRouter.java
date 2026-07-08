package com.orca.gateway.router;

import com.orca.gateway.provider.LlmProvider;

/**
 * Provider 路由器: 按 model 别名选择 LlmProvider。
 * P1 单选(supports 命中第一个); P2 加 RoutingPolicy 做加权/故障转移。
 */
public interface ProviderRouter {

    LlmProvider route(String model);
}
