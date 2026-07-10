package com.orca.agent.tool;

import com.orca.common.exception.BizException;
import com.orca.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 工具注册中心: Spring 收集所有 Tool bean, 按 name 路由。
 *
 * 面考点: 仿 NodeExecutor 模式, List<Tool> 注入 + supportType 路由, OCP。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolRegistry {

    private final List<Tool> tools;

    /** 按 name 路由执行; 未知工具抛 AGENT_TOOL_ERROR */
    public String invoke(String name, String args) {
        Tool tool = resolve(name);
        try {
            log.info("[tool] 执行 name={} args={}", name, args);
            return tool.execute(args);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("[tool] 执行异常 name={}", name, e);
            throw new BizException(ErrorCode.AGENT_TOOL_ERROR, "工具 " + name + " 执行异常: " + e.getMessage());
        }
    }

    /** 列出工具描述(注入 system prompt 供 LLM/脚本选择) */
    public String describeTools(List<String> allowedNames) {
        if (tools == null || tools.isEmpty()) return "tools: (无)\n";
        StringBuilder sb = new StringBuilder("tools:\n");
        for (Tool t : tools) {
            if (allowedNames == null || allowedNames.isEmpty() || allowedNames.contains(t.name())) {
                sb.append("  - ").append(t.name()).append(": ").append(t.description()).append('\n');
            }
        }
        return sb.toString();
    }

    public Tool resolve(String name) {
        return tools.stream()
                .filter(t -> t.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.AGENT_TOOL_ERROR, "未知工具: " + name));
    }

    public Map<String, Tool> all() {
        return tools.stream().collect(Collectors.toMap(Tool::name, t -> t));
    }
}
