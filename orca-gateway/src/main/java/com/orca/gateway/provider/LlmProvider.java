package com.orca.gateway.provider;

import com.orca.gateway.provider.model.ChatChunk;
import com.orca.gateway.provider.model.ChatRequest;
import com.orca.gateway.provider.model.ChatResponse;
import reactor.core.publisher.Flux;

/**
 * LLM Provider 热插拔契约。
 *
 * 面试点: 为什么用接口而非具体类?
 *  → 网关与具体模型解耦, 后续加 OpenAI/DeepSeek/智谱 只需新增一个 @Component 实现此接口,
 *    ProviderRouter 按 supports(model) 自动路由, 网关核心(限流/配额/计费)零改动。
 *  → 这就是"对扩展开放, 对修改关闭"(OCP)。
 *
 * 同步 chat: 阻塞返回完整响应
 * 流式 streamChat: 返回 Flux<ChatChunk>, 网关透传为 SSE
 */
public interface LlmProvider {

    /** Provider 名称, 用于 call_log.provider */
    String name();

    /** 是否支持指定 model 别名 */
    boolean supports(String model);

    /** 同步对话 */
    ChatResponse chat(ChatRequest request);

    /** 流式对话 */
    Flux<ChatChunk> streamChat(ChatRequest request);
}
