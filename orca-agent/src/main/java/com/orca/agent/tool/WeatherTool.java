package com.orca.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 天气工具(Mock): 真实场景调天气 API。
 * P4 用固定数据演示 ReAct 的真实工具调用 + observation。
 */
@Component
@RequiredArgsConstructor
public class WeatherTool implements Tool {

    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "weather";
    }

    @Override
    public String description() {
        return "查询城市天气, 参数 {\"city\":\"城市名\"}";
    }

    @Override
    public String execute(String args) throws Exception {
        String city = objectMapper.readTree(args).path("city").asText("未知");
        // Mock: 固定天气数据(真实场景调天气 API)
        return "{\"city\":\"" + city + "\",\"temp\":22,\"condition\":\"晴\",\"humidity\":45}";
    }
}
