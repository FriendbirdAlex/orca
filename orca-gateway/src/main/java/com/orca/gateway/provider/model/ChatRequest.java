package com.orca.gateway.provider.model;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * OpenAI 风格 chat 请求体。
 * 面试点: 为何对齐 OpenAI 协议 → 生态兼容, 后续真实 Provider 替换零改动契约。
 */
@Data
public class ChatRequest {

    /** 模型别名, 网关按此路由 Provider */
    private String model = "mock";

    /** 对话消息 */
    @NotEmpty(message = "messages 不能为空")
    private List<Message> messages;

    /** 最大生成 token 数(预扣上界) */
    private Integer maxTokens = 1024;

    /** 采样温度 */
    private Double temperature = 0.7;

    /** 是否流式(也可由 query param stream=true 决定) */
    private Boolean stream = false;

    @Data
    public static class Message {
        @NotNull
        private String role;       // system / user / assistant
        @NotNull
        private String content;
    }
}
