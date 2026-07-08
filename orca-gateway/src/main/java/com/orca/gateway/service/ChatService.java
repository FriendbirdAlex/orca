package com.orca.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orca.common.context.TenantContext;
import com.orca.common.context.TenantInfo;
import com.orca.common.exception.BizException;
import com.orca.common.exception.ErrorCode;
import com.orca.common.result.Result;
import com.orca.gateway.billing.CallLog;
import com.orca.gateway.billing.CallLogService;
import com.orca.gateway.limiter.LimitType;
import com.orca.gateway.limiter.RateLimiter;
import com.orca.gateway.provider.TokenEstimator;
import com.orca.gateway.provider.model.ChatChunk;
import com.orca.gateway.provider.model.ChatRequest;
import com.orca.gateway.provider.model.ChatResponse;
import com.orca.gateway.quota.QuotaManager;
import com.orca.gateway.quota.QuotaResult;
import com.orca.gateway.router.ProviderRouter;
import com.orca.gateway.provider.LlmProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 网关编排核心: 鉴权上下文 → 限流 → 配额 → 路由 → Provider → 结算 → 记账。
 *
 * 两条链路:
 *  1. chat(): 同步, 全程在请求线程(ThreadLocal 可用)
 *  2. streamChat(): 流式, 预扣在请求线程, 推送在 Reactor 线程
 *     → tenantId 必须显式捕获进闭包(Reactor 线程不继承 ThreadLocal)
 *     → 末包 usage 用 AtomicReference 透出, doOnComplete 读取结算
 *
 * 预扣/结算模型(面试核心):
 *  - rpm 预扣 1(不退, 简化)
 *  - tpm 预扣 maxTokens(上界), 完成后退回 (maxTokens - actualCompletion)
 *  - quota 预扣 (prompt + maxTokens), 完成后退回 (reserved - actualTotal)
 *  - 失败: 按规则退回已扣额度, 保证不"白扣"
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final ProviderRouter router;
    private final RateLimiter rateLimiter;
    private final QuotaManager quotaManager;
    private final TokenEstimator estimator;
    private final CallLogService callLogService;
    private final ObjectMapper objectMapper;

    // ==================== 同步对话 ====================

    public Result<ChatResponse> chat(ChatRequest req) {
        TenantInfo t = TenantContext.require();
        long start = System.currentTimeMillis();
        int prompt = estimator.estimate(promptText(req));
        int maxTokens = safeMaxTokens(req);
        int reserve = prompt + maxTokens;

        // 1) 限流 rpm(1)
        if (!rateLimiter.tryConsume(t.tenantId(), LimitType.RPM, 1, t.rpm()).allowed()) {
            throw new BizException(ErrorCode.GW_LIMITED);
        }
        // 2) 限流 tpm(maxTokens) 预扣
        if (!rateLimiter.tryConsume(t.tenantId(), LimitType.TPM, maxTokens, t.tpm()).allowed()) {
            // tpm 失败, 退回 rpm(简化: 不退, 留作权衡点)
            throw new BizException(ErrorCode.GW_LIMITED);
        }
        // 3) 配额预扣
        QuotaResult quota = quotaManager.reserve(t.tenantId(), reserve, t.dailyQuota());
        if (!quota.allowed()) {
            // 配额失败, 退回 tpm
            rateLimiter.refund(t.tenantId(), LimitType.TPM, maxTokens, t.tpm());
            throw new BizException(ErrorCode.GW_QUOTA_EXCEEDED);
        }

        // 4) 路由 + 调用
        LlmProvider provider = router.route(req.getModel());
        try {
            ChatResponse resp = provider.chat(req);
            int actualTotal = resp.getUsage().getTotalTokens();
            int actualCompletion = resp.getUsage().getCompletionTokens();
            // 5) 结算退回多余
            settle(t, maxTokens, actualCompletion, reserve, actualTotal);
            // 6) 记账
            record(t, req, provider.name(), false, resp.getUsage(), System.currentTimeMillis() - start, null);
            return Result.success(resp);
        } catch (BizException e) {
            // Provider 路由类业务异常, 不退 quota(已是路由失败)
            throw e;
        } catch (Exception e) {
            // Provider 调用失败: 全额退回 tpm + quota
            rateLimiter.refund(t.tenantId(), LimitType.TPM, maxTokens, t.tpm());
            quotaManager.refund(t.tenantId(), reserve, t.dailyQuota());
            record(t, req, provider.name(), false, null, System.currentTimeMillis() - start, ErrorCode.GW_UPSTREAM_ERROR.getCode());
            throw new BizException(ErrorCode.GW_UPSTREAM_ERROR, e.getMessage());
        }
    }

    // ==================== 流式对话 SSE ====================

    public Flux<ServerSentEvent<String>> streamChat(ChatRequest req) {
        TenantInfo t = TenantContext.require();
        // ★ 关键: 显式捕获, Flux 内 Reactor 线程不继承 ThreadLocal
        final long tenantId = t.tenantId();
        final int rpm = t.rpm();
        final int tpm = t.tpm();
        final long dailyQuota = t.dailyQuota();
        final String provider0;
        final long start = System.currentTimeMillis();
        final int maxTokens = safeMaxTokens(req);
        final int prompt = estimator.estimate(promptText(req));
        final int reserve = prompt + maxTokens;

        // 1-3) 预扣(同步段, 请求线程)
        if (!rateLimiter.tryConsume(tenantId, LimitType.RPM, 1, rpm).allowed()) {
            throw new BizException(ErrorCode.GW_LIMITED);
        }
        if (!rateLimiter.tryConsume(tenantId, LimitType.TPM, maxTokens, tpm).allowed()) {
            throw new BizException(ErrorCode.GW_LIMITED);
        }
        QuotaResult quota = quotaManager.reserve(tenantId, reserve, dailyQuota);
        if (!quota.allowed()) {
            rateLimiter.refund(tenantId, LimitType.TPM, maxTokens, tpm);
            throw new BizException(ErrorCode.GW_QUOTA_EXCEEDED);
        }

        // 4) 路由(同步段, 可能抛 GW_NO_PROVIDER → 退回)
        LlmProvider provider;
        try {
            provider = router.route(req.getModel());
        } catch (BizException e) {
            rateLimiter.refund(tenantId, LimitType.TPM, maxTokens, tpm);
            quotaManager.refund(tenantId, reserve, dailyQuota);
            throw e;
        }
        final LlmProvider p = provider;
        final String providerName = p.name();

        // 末包 usage 透出 holder(Reactor 跨阶段传值)
        AtomicReference<ChatResponse.Usage> usageRef = new AtomicReference<>();

        // 5) 流式推送(Reactor 线程)
        return p.streamChat(req)
                .map(chunk -> {
                    if (chunk.isFinal() && chunk.usage() != null) {
                        usageRef.set(chunk.usage());
                    }
                    return toSse("message", writeJson(chunk));
                })
                .concatWith(Flux.just(toSse("message", "[DONE]")))
                .onErrorResume(e -> {
                    // 流中错误: 全额退回 + 记账
                    rateLimiter.refund(tenantId, LimitType.TPM, maxTokens, tpm);
                    quotaManager.refund(tenantId, reserve, dailyQuota);
                    recordById(tenantId, t, req, providerName, true, null,
                            System.currentTimeMillis() - start, ErrorCode.GW_UPSTREAM_ERROR.getCode());
                    return Flux.just(toSse("error", writeJson(Result.fail(ErrorCode.GW_UPSTREAM_ERROR.getCode(), e.getMessage()))));
                })
                .doOnComplete(() -> {
                    // 正常结束: 用末包 usage 结算
                    ChatResponse.Usage u = usageRef.get();
                    int actualTotal = u == null ? reserve : u.getTotalTokens();
                    int actualCompletion = u == null ? maxTokens : u.getCompletionTokens();
                    settleById(tenantId, dailyQuota, tpm, maxTokens, actualCompletion, reserve, actualTotal);
                    recordById(tenantId, t, req, providerName, true, u,
                            System.currentTimeMillis() - start, null);
                });
    }

    // ==================== 结算/记账 helper ====================

    private void settle(TenantInfo t, int maxTokens, int actualCompletion, int reserve, int actualTotal) {
        // tpm 退回 (maxTokens - actualCompletion)
        int tpmRefund = Math.max(0, maxTokens - actualCompletion);
        if (tpmRefund > 0) {
            rateLimiter.refund(t.tenantId(), LimitType.TPM, tpmRefund, t.tpm());
        }
        // quota 退回 (reserve - actualTotal)
        int quotaRefund = Math.max(0, reserve - actualTotal);
        if (quotaRefund > 0) {
            quotaManager.refund(t.tenantId(), quotaRefund, t.dailyQuota());
        }
    }

    private void settleById(long tenantId, long dailyQuota, int tpm, int maxTokens, int actualCompletion, int reserve, int actualTotal) {
        int tpmRefund = Math.max(0, maxTokens - actualCompletion);
        if (tpmRefund > 0) {
            rateLimiter.refund(tenantId, LimitType.TPM, tpmRefund, tpm);
        }
        int quotaRefund = Math.max(0, reserve - actualTotal);
        if (quotaRefund > 0) {
            quotaManager.refund(tenantId, quotaRefund, dailyQuota);
        }
    }

    private void record(TenantInfo t, ChatRequest req, String providerName, boolean stream,
                        ChatResponse.Usage usage, long latencyMs, Integer errorCode) {
        recordById(t.tenantId(), t, req, providerName, stream, usage, latencyMs, errorCode);
    }

    private void recordById(long tenantId, TenantInfo t, ChatRequest req, String providerName, boolean stream,
                            ChatResponse.Usage usage, long latencyMs, Integer errorCode) {
        CallLog log = new CallLog();
        log.setTenantId(tenantId);
        log.setApiKeyId(t == null ? null : t.apiKeyId());
        log.setRequestId(UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        log.setProvider(providerName);
        log.setModel(req.getModel());
        log.setStream(stream ? 1 : 0);
        if (usage != null) {
            log.setPromptTokens(usage.getPromptTokens());
            log.setCompletionTokens(usage.getCompletionTokens());
            log.setTotalTokens(usage.getTotalTokens());
        } else {
            log.setPromptTokens(0);
            log.setCompletionTokens(0);
            log.setTotalTokens(0);
        }
        log.setLatencyMs((int) latencyMs);
        log.setStatus(errorCode == null ? "success" : "fail");
        log.setErrorCode(errorCode);
        log.setCreatedAt(LocalDateTime.now());
        callLogService.record(log);
    }

    // ==================== 工具 ====================

    private String promptText(ChatRequest req) {
        if (req.getMessages() == null) return "";
        StringBuilder sb = new StringBuilder();
        req.getMessages().forEach(m -> sb.append(m.getContent()).append('\n'));
        return sb.toString();
    }

    private int safeMaxTokens(ChatRequest req) {
        return req.getMaxTokens() == null || req.getMaxTokens() <= 0 ? 1024 : req.getMaxTokens();
    }

    private ServerSentEvent<String> toSse(String event, String data) {
        return ServerSentEvent.<String>builder().event(event).data(data).build();
    }

    private String writeJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{\"error\":\"serialize failed\"}";
        }
    }
}
