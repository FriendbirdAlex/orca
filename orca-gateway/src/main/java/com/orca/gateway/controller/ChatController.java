package com.orca.gateway.controller;

import com.orca.common.result.Result;
import com.orca.gateway.provider.model.ChatRequest;
import com.orca.gateway.provider.model.ChatResponse;
import com.orca.gateway.service.ChatService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * OpenAI 风格 chat 接口。
 * - POST /v1/chat/completions            同步
 * - POST /v1/chat/completions?stream=true SSE 流式
 *
 * 面考点: Spring MVC 下返回 Flux<ServerSentEvent> 也能做 SSE(容器是 Tomcat, WebFlux 作库);
 *        stream=true 用 params 路由区分同 path 的两个方法。
 */
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    @PostMapping(value = "/chat/completions", produces = MediaType.APPLICATION_JSON_VALUE)
    public Result<ChatResponse> chat(@Valid @RequestBody ChatRequest req) {
        return chatService.chat(req);
    }

    @PostMapping(value = "/chat/completions", params = "stream=true",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(@Valid @RequestBody ChatRequest req) {
        return chatService.streamChat(req);
    }
}
