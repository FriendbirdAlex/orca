package com.orca.agent.react;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * ReAct 单步记录。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReActStep {
    private int step;          // 第几步(1-based)
    private String thought;    // LLM 输出文本(含 Thought/Action/Final)
    private String action;     // 工具名, 如 weather; Final 步为 null
    private String actionArgs; // 工具参数 JSON
    private String observation;// 工具返回; Final 步为 null
}
