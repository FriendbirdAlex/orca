package com.orca.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;

/**
 * 计算器工具: 求值算术表达式。
 * 参数 {"expr":"22*3"} → 66
 */
@Component
@RequiredArgsConstructor
public class CalculatorTool implements Tool {

    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "calculator";
    }

    @Override
    public String description() {
        return "计算算术表达式, 参数 {\"expr\":\"表达式\"}, 仅支持数字与 + - * / ( )";
    }

    @Override
    public String execute(String args) throws Exception {
        String expr = objectMapper.readTree(args).path("expr").asText("");
        // 安全校验: 只允许数字与运算符(防注入)
        if (!expr.matches("^[0-9+\\-*/().\\s]+$") || expr.isBlank()) {
            throw new IllegalArgumentException("非法表达式: " + expr);
        }
        ScriptEngine engine = new ScriptEngineManager().getEngineByName("js");
        if (engine == null) {
            // 无 js 引擎(fallback): 简单整数四则
            return "{\"expr\":\"" + expr + "\",\"result\":\"" + evalSimple(expr) + "\"}";
        }
        Object result = engine.eval(expr);
        return "{\"expr\":\"" + expr + "\",\"result\":" + result + "}";
    }

    private String evalSimple(String expr) {
        // 极简 fallback, 仅演示用
        try {
            String[] parts = expr.split("\\*");
            if (parts.length == 2) return String.valueOf(Integer.parseInt(parts[0].trim()) * Integer.parseInt(parts[1].trim()));
        } catch (Exception ignored) {}
        return "0";
    }
}
