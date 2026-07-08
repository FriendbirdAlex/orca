package com.orca.gateway.config;

import com.orca.gateway.provider.LlmProvider;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

/**
 * Resilience4j 熔断配置。
 *
 * 面试点:
 *  1. 程序式注册(非注解), 每个 Provider name 一个熔断器, registry 缓存实例
 *  2. ignore BizException: 限流/配额/熔断本身是业务决策, 不是上游故障, 不应计入熔断统计
 *  3. 不引 resilience4j-reactor: 流式用手动 acquirePermission + doOnComplete/doOnError 回调, 更轻更可控
 *
 * 状态机: CLOSED → 错误率超阈 → OPEN(快速失败) → 等 waitDuration → HALF_OPEN(放 N 个试探)
 *       → 成功率达标 → CLOSED; 仍失败 → OPEN
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class Resilience4jConfig {

    private final List<LlmProvider> providers;

    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)               // 错误率 50% 触发熔断
                .slowCallRateThreshold(60)              // 慢调用占比 60% 触发
                .slowCallDurationThreshold(Duration.ofSeconds(3))
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(20)                  // 滑动窗口 20 次
                .minimumNumberOfCalls(10)               // 至少 10 次才计算
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(5)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                // 业务异常不计入熔断(限流/配额/熔断本身), 只记真正的上游故障
                .ignoreExceptions(com.orca.common.exception.BizException.class)
                .build();
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(config);
        // 为每个 Provider 预注册熔断器(同一 name 复用实例); 放 @Bean 方法内避免 @PostConstruct 自循环
        if (providers != null) {
            providers.forEach(p -> {
                registry.circuitBreaker(p.name());
                log.info("[resilience4j] 已注册熔断器: {}", p.name());
            });
        }
        log.info("[resilience4j] CircuitBreakerRegistry 已创建");
        return registry;
    }
}

