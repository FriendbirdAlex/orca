package com.orca.gateway.router;

import com.orca.common.exception.BizException;
import com.orca.common.exception.ErrorCode;
import com.orca.gateway.provider.LlmProvider;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 默认路由: 注入所有 LlmProvider bean, 按 supports(model) 选第一个命中,
 * 返回时用 CircuitBreakerLlmProvider 装饰(熔断)。
 *
 * 面试点(OCP): 新增真实 Provider 只需加一个 @Component 实现 LlmProvider,
 *             supports 命中对应 model 前缀, 路由自动生效, 无需改本类;
 *             熔断由装饰器透明织入, ChatService 无感。
 */
@Slf4j
@Primary
@Component
@RequiredArgsConstructor
public class DefaultProviderRouter implements ProviderRouter {

    private final List<LlmProvider> providers;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    @Override
    public LlmProvider route(String model) {
        if (providers != null) {
            for (LlmProvider p : providers) {
                if (p.supports(model)) {
                    log.debug("[router] model={} -> provider={}", model, p.name());
                    // 用熔断装饰器包装: registry 按 name 取/建熔断器(同一 name 复用实例)
                    CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker(p.name());
                    return new CircuitBreakerLlmProvider(p, cb);
                }
            }
        }
        throw new BizException(ErrorCode.GW_NO_PROVIDER, "无可用 Provider for model=" + model);
    }
}
