# P4 — Agent 编排（ReAct + 多 Agent + 短期记忆 + Human-in-loop）

## 目标

在 `orca-agent` 模块实现 Agent runtime，和 P3 工作流联动（Agent 作为 AGENT 节点）。覆盖 ReAct 循环、Tool Calling、多 Agent 协同、短期记忆、防失控、Human-in-loop。完成四阶段路线图。

## 核心矛盾与解法：脚本化 ReAct

MockLlmProvider 返回固定文本无法做工具调用决策。**解法**：给 MockLlmProvider 加 react 模式（system message 含 `[REACT_MODE]` 标记时触发），按 system 注入的 step/observations 脚本输出 `Action: tool(args)` / `Final Answer: ...`。ReActLoop 解析此结构驱动**真实 Tool 执行** → 真实 observation → 再调 LLM → 直到 Final。

ReAct 循环结构真实（Tool/Memory/循环/上限都是真的），仅"推理"脚本化（与 Mock 模拟真模型一致）。生产换真模型只需让它按同格式输出，循环不动（OCP）。普通 chat 不含标记走原逻辑，P1-P3 验证语义不变。

## 类设计（orca-agent 4 包 + workflow 联动）

- **tool**：`Tool` 接口(name/description/execute) + `ToolRegistry`(Spring 收集 List<Tool> 按 name 路由) + `WeatherTool`/`CalculatorTool`/`HttpCallTool`(@RequireApproval) + `@RequireApproval` 注解
- **memory**：`ShortTermMemory` 接口 + `RedisShortTermMemory`(Redis List RPUSH+LRANGE 按 runId 隔离，TTL 1h)
- **react**：`ReActStep`/`ReActResult` + `ReActProtocol`(正则解析 Action/Final) + `ReActLoop`(循环核心)
- **runtime**：`AgentRuntime`(组装入口，TenantContext 重建) + `AgentDefinition`
- **config**：`AgentProperties`(max-steps/max-tokens/step-timeout-ms)
- **controller**：`AgentController`(/v1/agents/run 同步运行) + dto
- **workflow 联动**：`NodeType` 加 AGENT + `AgentNodeExecutor`(照搬 LlmNodeExecutor 重建) + workflow pom 加 agent 依赖

## ReActLoop 循环核心

每步：① buildReActRequest（system 注入 [REACT_MODE]+step+query+工具列表+历史 observation）② 调 ChatService.chat（享限流/配额/熔断/缓存/记账）③ 累加 totalTokens 查成本上限 ④ 解析 LLM 文本（先 Final 再 Action）⑤ Action→ToolRegistry.invoke→observation（工具错误喂回 LLM 自纠）⑥ memory.append。步数上限未到 Final → 抛 AGENT_LOOP_LIMIT。

**关键**：步数/observations 经 system message 传递（ChatRequest 无 step 字段），MockLlmProvider 解析它驱动脚本，生产真模型读它推理——脚本化 ReAct 的核心契约。

## 多 Agent 协同（DAG 编排）

AGENT 节点用 edges 串联，上游 output.finalAnswer 喂下游（`${agentId.finalAnswer}` 模板渲染），merge 节点等所有上游 SUCCEEDED。完全复用 P3 DAG/事件/Saga，零新机制。

## Human-in-loop（复用 P3）

DAG 里 `HUMAN→AGENT` 组合即"人工确认后执行 Agent"，复用 HumanTaskNodeExecutor 暂停-恢复。独立接口高风险工具(@RequireApproval)拒绝执行返回跳过 observation。

## 防失控（4 闸）

步数上限(AGENT_LOOP_LIMIT 400001) + 成本累加(AGENT_COST_LIMIT 400002) + 工具异常喂回自纠(AGENT_TOOL_ERROR 400003) + 单步超时(沿用网关熔断)。

## 持久化

独立 /v1/agents/run → Redis 短期记忆(TTL 1h)；工作流 AGENT 节点 → P3 事件溯源(NODE_SUCCEEDED payload 含 steps)。

## 面试考点映射

ReAct 循环、Tool Calling(Tool+ToolRegistry OCP)、多 Agent 协同(DAG 编排复用P3)、短期记忆(Redis List 按 runId 隔离)、防失控(步数/成本/工具/超时)、Human-in-loop(HUMAN→AGENT 复用P3)、Agent 作为工作流节点(OCP)、TenantContext 异步传递(快照重建)、脚本化 ReAct 解耦([REACT_MODE]标记)、治理复用(每步经ChatService)

## 验证（已通过）

| 验证项 | 结果 |
|--------|------|
| 独立 Agent ReAct | ✅ 2步(step1 Action:weather→observation, step2 Final Answer)，finalAnswer 拼装 observation |
| 工作流 Agent 节点 | ✅ AGENT 节点 SUCCEEDED，output 含 ReAct steps，query="上海天气"正确抽城市 |
| 步数上限 | ✅ maxSteps=1 → AGENT_LOOP_LIMIT(400001) |
| 普通 chat 回归 | ✅ 无[REACT_MODE]走原 Mock 模板，P1-P3 验证语义不变 |

## P4 边界（不做）

真实模型 function calling、长期记忆向量库、Agent 自主创建子工作流、复杂工具 schema 校验、Agent 运行历史 DB 持久化、流式 ReAct(SSE)

## 踩坑（实现时已规避）

1. MockLlmProvider react 分支只在 system message 含 `[REACT_MODE]` 时触发，普通 chat 走原逻辑
2. AgentNodeExecutor/AgentRuntime 的 TenantContext.set/clear 必须 try-finally（虚拟线程池复用防串租户）
3. ReActLoop 每步调 ChatService.chat（非直接调 MockLlmProvider），享治理
4. 步数/observations 经 system message 传递（ChatRequest 无 step 字段）
5. 工具错误喂回 LLM 自纠（observation="[工具错误]..."），不直接终止
6. AgentNodeExecutor 在 workflow 模块（NodeExecutor 接口在 workflow），workflow pom 加 agent 依赖（agent 不依赖 workflow 避免循环）
7. 独立 /v1/agents/run 同步返回（短任务），长时 Agent 走工作流 AGENT 节点异步+事件溯源
8. Java17+ 无 Nashorn，CalculatorTool 加 fallback（无 js 引擎时简单整数四则）
