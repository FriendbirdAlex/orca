package com.orca.agent.memory;

import com.orca.agent.react.ReActStep;

import java.util.List;

/**
 * Agent 短期记忆: 存 ReAct 每步记录(append-only, 按 runId 隔离)。
 *
 * 面试点: Redis List(RPUSH+LRANGE) 天然保序 + append-only 契合 ReAct 步骤流;
 *        TTL 防泄漏; runId 隔离多并发 Agent 互不串。
 */
public interface ShortTermMemory {

    void append(String runId, ReActStep step);

    List<ReActStep> load(String runId);

    void clear(String runId);
}
