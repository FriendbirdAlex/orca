package com.orca.agent.react;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * ReAct 循环结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReActResult {
    private String finalAnswer;
    private List<ReActStep> steps;
    private int totalTokens;
    private boolean truncated;      // 是否因上限截断
    private String truncateReason;  // LOOP_LIMIT / COST_LIMIT
}
