package com.orca.gateway.provider.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * OpenAI 风格 chat 响应体(同步)。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatResponse {

    private String id;
    private String model;
    private List<Choice> choices;
    private Usage usage;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Choice {
        private int index;
        private Message message;
        private String finishReason;   // stop / length / ...
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Message {
        private String role;
        private String content;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Usage {
        private int promptTokens;
        private int completionTokens;
        private int totalTokens;
    }

    public static ChatResponse of(String id, String model, String text, int prompt, int completion) {
        return ChatResponse.builder()
                .id(id)
                .model(model)
                .choices(List.of(Choice.builder()
                        .index(0)
                        .message(Message.builder().role("assistant").content(text).build())
                        .finishReason("stop")
                        .build()))
                .usage(Usage.builder()
                        .promptTokens(prompt)
                        .completionTokens(completion)
                        .totalTokens(prompt + completion)
                        .build())
                .build();
    }
}
