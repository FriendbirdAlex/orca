package com.orca.agent.controller.dto;

import com.orca.agent.react.ReActResult;
import com.orca.agent.react.ReActStep;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentRunResponse {
    private String finalAnswer;
    private List<ReActStep> steps;
    private int totalTokens;
    private boolean truncated;

    public static AgentRunResponse from(ReActResult r) {
        return AgentRunResponse.builder()
                .finalAnswer(r.getFinalAnswer())
                .steps(r.getSteps())
                .totalTokens(r.getTotalTokens())
                .truncated(r.isTruncated())
                .build();
    }
}
