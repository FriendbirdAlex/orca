package com.orca.gateway.router;

import com.orca.common.exception.BizException;
import com.orca.common.exception.ErrorCode;
import com.orca.gateway.provider.LlmProvider;
import com.orca.gateway.provider.model.ChatChunk;
import com.orca.gateway.provider.model.ChatRequest;
import com.orca.gateway.provider.model.ChatResponse;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

import java.util.concurrent.TimeUnit;

/**
 * 熔断装饰器: 包在真实 LlmProvider 外, 由 DefaultProviderRouter 返回。
 *
 * 同步 chat: cb.executeSupplier 全装饰, CallNotPermittedException → BizException(GW_CIRCUIT_OPEN)
 * 流式 streamChat: 同步预检 tryAcquirePermission(false→抛), 返回的 Flux 用 doOnComplete/doOnError 回灌结果
 *
 * 面试点: 装饰器模式解耦熔断与业务; 流式不用 resilience4j-reactor, 手动回调更轻;
 *        熔断开启时调用未触达上游, ChatService 捕获后按失败语义退回已扣额度。
 */
@Slf4j
@RequiredArgsConstructor
public class CircuitBreakerLlmProvider implements LlmProvider {

    private final LlmProvider delegate;
    private final CircuitBreaker circuitBreaker;

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public boolean supports(String model) {
        return delegate.supports(model);
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        try {
            return circuitBreaker.executeSupplier(() -> delegate.chat(request));
        } catch (CallNotPermittedException e) {
            log.warn("[circuit-breaker] {} 已熔断, 拒绝调用", delegate.name());
            throw new BizException(ErrorCode.GW_CIRCUIT_OPEN, "Provider " + delegate.name() + " 熔断中");
        }
    }

    @Override
    public Flux<ChatChunk> streamChat(ChatRequest request) {
        // 同步预检: 熔断开启则直接抛(在请求线程, ChatService 可捕获退回额度)
        if (!circuitBreaker.tryAcquirePermission()) {
            log.warn("[circuit-breaker] {} 已熔断, 拒绝流式调用", delegate.name());
            throw new BizException(ErrorCode.GW_CIRCUIT_OPEN, "Provider " + delegate.name() + " 熔断中");
        }
        long start = System.nanoTime();
        // 回灌结果: 完成→onSuccess, 错误→onError(计入熔断统计)
        return delegate.streamChat(request)
                .doOnComplete(() -> circuitBreaker.onSuccess(System.nanoTime() - start, TimeUnit.NANOSECONDS))
                .doOnError(e -> circuitBreaker.onError(System.nanoTime() - start, TimeUnit.NANOSECONDS, e));
    }
}
