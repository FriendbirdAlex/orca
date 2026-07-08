package com.orca.gateway.provider;

import com.orca.gateway.config.GatewayProperties;
import com.orca.gateway.provider.model.ChatChunk;
import com.orca.gateway.provider.model.ChatRequest;
import com.orca.gateway.provider.model.ChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Mock LLM Provider: P1 不接真实模型, 用固定模板模拟。
 *
 * 设计: 先生成完整文本 → 切片 → delayElements 模拟逐 token 流式 → 末包带 usage。
 * 这同时天然带出 SSE 背压(节流即最简背压), 为 P2 真实 Provider(WebClient 流式)留好接口。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MockLlmProvider implements LlmProvider {

    private final TokenEstimator estimator;
    private final GatewayProperties props;

    @Override
    public String name() {
        return "mock";
    }

    @Override
    public boolean supports(String model) {
        // P1 只有一个 Provider, 接所有 model(真实模型场景按前缀匹配, 如 gpt-* / deepseek-*)
        return true;
    }

    @Override
    public ChatResponse chat(ChatRequest req) {
        String text = buildMockText(req);
        int prompt = estimator.estimate(promptText(req));
        int completion = Math.min(estimator.estimate(text), safeMaxTokens(req));
        log.debug("[mock] chat prompt={} completion={}", prompt, completion);
        return ChatResponse.of(genId(), req.getModel(), text, prompt, completion);
    }

    @Override
    public Flux<ChatChunk> streamChat(ChatRequest req) {
        String text = buildMockText(req);
        int prompt = estimator.estimate(promptText(req));
        int completion = Math.min(estimator.estimate(text), safeMaxTokens(req));
        String id = genId();
        List<String> slices = slice(text, props.getMock().getResponseChunkCount());
        long delayMs = props.getMock().getChunkDelayMs();

        log.debug("[mock] stream slices={} delay={}ms prompt={} completion={}", slices.size(), delayMs, prompt, completion);

        // 模拟逐 token: delayElements 节流; 末包 concatWith 带 usage + finish_reason=stop
        return Flux.fromIterable(slices)
                .map(s -> ChatChunk.delta(id, req.getModel(), s))
                .delayElements(Duration.ofMillis(delayMs))
                .concatWith(Flux.just(ChatChunk.finalChunk(id, req.getModel(), prompt, completion)));
    }

    // ---- helpers ----

    private String buildMockText(ChatRequest req) {
        String lastUser = lastUserMessage(req);
        // 模拟一段固定 + 拼接的回复, 长度随 prompt 略变以便观察 token 计量
        int paragraphs = 1 + (lastUser.length() % 3);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < paragraphs; i++) {
            sb.append("[Mock Response #").append(i + 1).append("] ")
              .append("收到你的提问「").append(truncate(lastUser, 40)).append("」。")
              .append("这是一个 Mock Provider 的占位回复, 用于验证网关限流/配额/SSE 透传链路。")
              .append("随机种子=").append(ThreadLocalRandom.current().nextInt(1000)).append("。 ");
        }
        return sb.toString();
    }

    private String promptText(ChatRequest req) {
        if (req.getMessages() == null) return "";
        StringBuilder sb = new StringBuilder();
        req.getMessages().forEach(m -> sb.append(m.getContent()).append('\n'));
        return sb.toString();
    }

    private String lastUserMessage(ChatRequest req) {
        if (req.getMessages() == null) return "";
        return req.getMessages().stream()
                .filter(m -> "user".equalsIgnoreCase(m.getRole()))
                .reduce((a, b) -> b)   // 取最后一条 user
                .map(ChatRequest.Message::getContent)
                .orElse("");
    }

    private int safeMaxTokens(ChatRequest req) {
        return req.getMaxTokens() == null || req.getMaxTokens() <= 0 ? 1024 : req.getMaxTokens();
    }

    private List<String> slice(String text, int chunkCount) {
        if (chunkCount <= 1) {
            return List.of(text);
        }
        // 按字符数均分(真实场景按 token, 这里简化)
        int len = text.length();
        int size = Math.max(1, len / chunkCount);
        List<String> slices = new ArrayList<>(chunkCount);
        for (int i = 0; i < len; i += size) {
            slices.add(text.substring(i, Math.min(len, i + size)));
        }
        return slices;
    }

    private String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private String genId() {
        return "chatcmpl-mock-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
