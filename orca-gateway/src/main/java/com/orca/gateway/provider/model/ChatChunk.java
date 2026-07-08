package com.orca.gateway.provider.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 流式分片(OpenAI 风格 chunk)。
 * 中间分片: choices[0].delta.content 有值, finishReason=null
 * 末尾分片: choices[0].delta.content=null, finishReason=stop, usage 有值
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatChunk {

    private String id;
    private String model;
    private List<Choice> choices;
    private ChatResponse.Usage usage;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Choice {
        private int index;
        private Delta delta;
        private String finishReason;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Delta {
        private String role;
        private String content;
    }

    /** 中间内容分片 */
    public static ChatChunk delta(String id, String model, String content) {
        return ChatChunk.builder()
                .id(id)
                .model(model)
                .choices(List.of(Choice.builder()
                        .index(0)
                        .delta(Delta.builder().content(content).build())
                        .finishReason(null)
                        .build()))
                .usage(null)
                .build();
    }

    /** 末尾分片: usage + finish_reason=stop */
    public static ChatChunk finalChunk(String id, String model, int prompt, int completion) {
        return ChatChunk.builder()
                .id(id)
                .model(model)
                .choices(List.of(Choice.builder()
                        .index(0)
                        .delta(Delta.builder().build())
                        .finishReason("stop")
                        .build()))
                .usage(ChatResponse.Usage.builder()
                        .promptTokens(prompt)
                        .completionTokens(completion)
                        .totalTokens(prompt + completion)
                        .build())
                .build();
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isFinal() {
        return choices != null && !choices.isEmpty()
                && "stop".equals(choices.get(0).getFinishReason());
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public ChatResponse.Usage usage() {
        return usage;
    }
}
