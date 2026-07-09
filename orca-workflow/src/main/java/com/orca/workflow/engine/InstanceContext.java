package com.orca.workflow.engine;

import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * 实例运行上下文(承载节点间数据传递 + 租户快照)。
 * 持久化到 workflow_instance.context(节点输出快照)。
 *
 * render: 简单模板渲染, ${nodeId.field} → 上游节点 output 对应字段; ${input.x} → 启动入参。
 */
@Data
public class InstanceContext {

    private Long instanceId;
    private String workflowCode;
    private Integer version;

    /** 启动入参 */
    private Map<String, Object> input = new HashMap<>();

    /** 节点输出: nodeId -> output map */
    private Map<String, Map<String, Object>> nodeOutputs = new HashMap<>();

    /** 租户快照 JSON(重建 TenantContext, 异步线程无 ThreadLocal) */
    private String tenantSnapshot;

    /**
     * 简单模板渲染: ${nodeId.field} / ${input.field} / ${input}。
     * 生产可用 SpEL, 这里用正则简化。
     */
    public String render(String template) {
        if (template == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\$\\{([^}]+)}").matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1).trim();
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(String.valueOf(resolve(key))));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 解析 ${a.b.c} 形式路径: 先按第一段定位(nodeId/input), 再逐层取 */
    private Object resolve(String path) {
        String[] parts = path.split("\\.");
        Object root;
        if ("input".equals(parts[0])) {
            root = input;
        } else {
            root = nodeOutputs.get(parts[0]);
        }
        Object cur = root;
        for (int i = 1; i < parts.length && cur instanceof Map<?, ?> map; i++) {
            cur = map.get(parts[i]);
        }
        return cur == null ? "" : cur;
    }
}
