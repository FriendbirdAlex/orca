package com.orca.agent.react;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orca.agent.memory.ShortTermMemory;
import com.orca.agent.tool.ToolRegistry;
import com.orca.common.exception.BizException;
import com.orca.common.exception.ErrorCode;
import com.orca.common.result.Result;
import com.orca.gateway.provider.model.ChatRequest;
import com.orca.gateway.provider.model.ChatResponse;
import com.orca.gateway.service.ChatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * ReAct 循环核心: Thought→Action→Observation→...→Final Answer。
 *
 * 每步调 ChatService.chat(★ 享限流/配额/熔断/缓存/记账全治理), 解析 LLM 输出得 Action/Final,
 * Action→ToolRegistry 执行→observation→memory 追加, 直到 Final 或步数/成本上限。
 *
 * 脚本化 ReAct: 步数/observations 经 system message 传给 MockLlmProvider 驱动脚本,
 *              生产真模型读 system prompt 推理, 循环逻辑不变(OCP)。
 *
 * 防失控: 步数上限(AGENT_LOOP_LIMIT) + 成本累加(AGENT_COST_LIMIT) + 工具异常喂回自纠(AGENT_TOOL_ERROR)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReActLoop {

    private final ChatService chatService;
    private final ToolRegistry toolRegistry;
    private final ShortTermMemory memory;
    private final ObjectMapper objectMapper;

    public ReActResult loop(String query, List<String> toolNames, String systemPrompt,
                            String runId, int maxSteps, long maxTokens) {
        int totalTokens = 0;
        List<ReActStep> steps = new ArrayList<>();
        String finalAnswer = null;

        for (int step = 1; step <= maxSteps; step++) {
            // ① 构造 ReAct 请求: system 注入 [REACT_MODE]+step+query+工具列表+历史 observation
            ChatRequest req = buildReActRequest(query, toolNames, systemPrompt, runId, step);
            // ② 调 ChatService.chat —— 享限流/配额/熔断/缓存/记账
            Result<ChatResponse> resp = chatService.chat(req);
            if (resp == null || !resp.isSuccess() || resp.getData() == null) {
                throw new BizException(ErrorCode.AGENT_TOOL_ERROR,
                        "LLM 推理失败: " + (resp == null ? "null" : resp.getMessage()));
            }
            String llmText = resp.getData().getChoices().get(0).getMessage().getContent();
            int stepTokens = resp.getData().getUsage().getTotalTokens();
            totalTokens += stepTokens;
            log.info("[react] step={} runId={} tokens={} text={}", step, runId, stepTokens,
                    llmText.length() > 80 ? llmText.substring(0, 80) + "..." : llmText);

            // ③ 成本上限检查
            if (totalTokens > maxTokens) {
                steps.add(saveStep(runId, step, llmText, null, null, null));
                memory.clear(runId);
                throw new BizException(ErrorCode.AGENT_COST_LIMIT,
                        "Agent 超出成本上限 " + maxTokens + "(已用 " + totalTokens + ")");
            }

            // ④ 解析 LLM 输出: 先 Final 再 Action
            Optional<String> finalOpt = ReActProtocol.parseFinal(llmText);
            if (finalOpt.isPresent()) {
                finalAnswer = finalOpt.get();
                steps.add(saveStep(runId, step, llmText, null, null, null));
                break;   // 到 Final, 结束
            }
            Optional<String[]> actionOpt = ReActProtocol.parseAction(llmText);
            if (actionOpt.isEmpty()) {
                // 协议违规: 无 Action/Final, 终止防失控
                steps.add(saveStep(runId, step, llmText, null, null, "[协议违规: 无 Action/Final]"));
                break;
            }

            // ⑤ Action → ToolRegistry 执行 → observation
            String toolName = actionOpt.get()[0];
            String toolArgs = actionOpt.get()[1];
            String observation;
            try {
                observation = toolRegistry.invoke(toolName, toolArgs);
            } catch (BizException e) {
                if (e.getCode() == ErrorCode.AGENT_TOOL_ERROR.getCode()) {
                    // 工具错误喂回 LLM 自纠(ReAct 韧性), 不直接终止
                    observation = "[工具错误] " + e.getMessage();
                } else throw e;
            }
            steps.add(saveStep(runId, step, llmText, toolName, toolArgs, observation));
        }

        memory.clear(runId);

        // ⑥ 步数上限未到 Final
        if (finalAnswer == null) {
            throw new BizException(ErrorCode.AGENT_LOOP_LIMIT,
                    "Agent 超出循环步数上限 " + maxSteps + " 未产出 Final Answer");
        }

        return ReActResult.builder()
                .finalAnswer(finalAnswer)
                .steps(steps)
                .totalTokens(totalTokens)
                .truncated(false)
                .build();
    }

    /** 构造 ReAct 请求: 把步数/历史 observation 塞进 system content(脚本契约) */
    private ChatRequest buildReActRequest(String query, List<String> toolNames,
                                          String systemPrompt, String runId, int step) {
        ChatRequest req = new ChatRequest();
        req.setModel("mock");
        req.setMaxTokens(512);
        req.setTemperature(0.3);

        List<ChatRequest.Message> messages = new ArrayList<>();
        List<ReActStep> history = memory.load(runId);
        String sys = "[REACT_MODE]\n"
                + "step: " + step + "\n"
                + "query: " + query + "\n"
                + "systemPrompt: " + (systemPrompt == null ? "" : systemPrompt) + "\n"
                + toolRegistry.describeTools(toolNames)
                + formatObservations(history)
                + "\n请输出 Action: tool(args) 调用工具, 或 Final Answer: 答案 结束。";
        messages.add(msg("system", sys));
        messages.add(msg("user", query));
        req.setMessages(messages);
        return req;
    }

    private String formatObservations(List<ReActStep> history) {
        if (history == null || history.isEmpty()) return "observations:\n(无)\n";
        StringBuilder sb = new StringBuilder("observations:\n");
        for (ReActStep s : history) {
            sb.append("  [").append(s.getStep()).append("] ")
                    .append(s.getAction() == null ? "-" : s.getAction())
                    .append(" -> ").append(s.getObservation() == null ? "-" : s.getObservation())
                    .append('\n');
        }
        return sb.toString();
    }

    private ReActStep saveStep(String runId, int step, String llmText,
                               String action, String args, String observation) {
        ReActStep s = ReActStep.builder()
                .step(step).thought(llmText)
                .action(action).actionArgs(args).observation(observation)
                .build();
        memory.append(runId, s);
        return s;
    }

    private ChatRequest.Message msg(String role, String content) {
        ChatRequest.Message m = new ChatRequest.Message();
        m.setRole(role);
        m.setContent(content);
        return m;
    }
}
