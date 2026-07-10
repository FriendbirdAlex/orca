package com.orca.agent.react;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ReAct 文本协议解析: 从 LLM 输出提取 Action/Final。
 *
 * 协议格式(Mock 脚本 + 生产真模型 system prompt 约定一致):
 *   Action: weather({"city":"北京"})
 *   或
 *   Final Answer: 北京今天晴, 22度
 *
 * 面考点: 脚本化 ReAct 的契约——Mock 与真模型输出同格式, ReActLoop 解析逻辑通用(OCP)。
 */
public final class ReActProtocol {

    private static final Pattern ACTION =
            Pattern.compile("Action:\\s*(\\w+)\\s*\\((.*)\\)", Pattern.DOTALL);
    private static final Pattern FINAL =
            Pattern.compile("Final Answer:\\s*(.+)", Pattern.DOTALL);

    private ReActProtocol() {}

    /** 解析 Action, 返回 [toolName, argsJson]; 无则 empty */
    public static Optional<String[]> parseAction(String text) {
        if (text == null) return Optional.empty();
        Matcher m = ACTION.matcher(text);
        if (m.find()) return Optional.of(new String[]{m.group(1), m.group(2)});
        return Optional.empty();
    }

    /** 解析 Final Answer; 无则 empty */
    public static Optional<String> parseFinal(String text) {
        if (text == null) return Optional.empty();
        Matcher m = FINAL.matcher(text);
        if (m.find()) return Optional.of(m.group(1).trim());
        return Optional.empty();
    }
}
