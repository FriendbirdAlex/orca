package com.orca.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * HTTP 调用工具: GET 外部接口。
 * 参数 {"url":"..."} → {status, body}
 * 标 @RequireApproval: 高风险(对外调用), 独立接口拒绝/工作流走 HUMAN 确认。
 */
@RequireApproval
@Component
@RequiredArgsConstructor
public class HttpCallTool implements Tool {

    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    @Override
    public String name() {
        return "http_call";
    }

    @Override
    public String description() {
        return "GET 调用外部接口, 参数 {\"url\":\"http地址\"}, 高风险需审批";
    }

    @Override
    public String execute(String args) throws Exception {
        String url = objectMapper.readTree(args).path("url").asText("");
        String body = restTemplate.getForObject(url, String.class);
        return "{\"url\":\"" + url + "\",\"body\":" + (body == null ? "null" : "\"" + body.replace("\"", "\\\"") + "\"") + "}";
    }
}
