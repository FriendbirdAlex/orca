# Orca 架构设计

> 面向后端的 AI 工作流编排平台 = **轻量 Temporal（编排）+ LLM 网关（治理）+ Agent runtime（推理）**

## 设计理念

三层不是拼凑：网关是所有 LLM 调用的唯一出口，Agent 是工作流的一等节点类型，引擎为 Agent 提供调度/重试/状态机骨架。本项目为个人秋招简历项目，从 0 自建并部署上线，覆盖后端面试常见技术点与常用中间件。

## 整体架构

```
┌──────────────────────────────────────────────────┐
│  接入层  orca-api   REST + SSE 推送 + 鉴权         │
├──────────────────────────────────────────────────┤
│  编排层  orca-workflow                            │
│   DSL解析→DAG→状态机→调度→重试/补偿(Saga)→事件溯源 │
│           ↕ 延迟队列(人工审批 Human-in-loop)       │
├──────────────────────────────────────────────────┤
│  推理层  orca-agent   ReAct 循环 / 多 Agent / 记忆 │
├──────────────────────────────────────────────────┤
│  治理层  orca-gateway                            │
│   Provider 适配 / 路由 / 限流 / 配额 / 语义缓存 /  │
│   熔断降级 / SSE 透传 / 计费                       │
├──────────────────────────────────────────────────┤
│  基础设施  MySQL Redis Kafka ES PgVector etcd      │
└──────────────────────────────────────────────────┘
```

## 模块职责

| 模块 | 职责 | 关键技术点 |
|------|------|-----------|
| `orca-api` | REST 接口、SSE 推送、API Key 鉴权 | Spring MVC、SSE、虚拟线程、拦截器 |
| `orca-workflow` | DSL 解析、DAG、状态机、调度、Saga、事件溯源 | 状态机、分布式锁、延迟队列 |
| `orca-agent` | ReAct 循环、Tool 注册、短期记忆 | Tool Calling、向量检索 |
| `orca-gateway` | Provider 适配、路由、限流、配额、缓存、熔断、计费 | Redis+Lua、熔断器、Reactor |
| `orca-common` | DTO、异常、全局处理、租户上下文 | 统一返回、ThreadLocal |
| `orca-observability` | Trace / Metrics | OpenTelemetry、Prometheus |

## 技术栈

Java 21 · Spring Boot 3.2 · MySQL 8 · Redis 7 · Kafka 3 (KRaft) · Elasticsearch 8 · PgVector · MyBatis-Plus · Redisson · Resilience4j · OpenTelemetry · Prometheus

## 关键架构决策

### 1. Servlet + 虚拟线程（非 WebFlux 全栈）

`orca-api` 引 `starter-web`（Tomcat），`orca-gateway` 的 `starter-webflux` 降级为库使用（只用 `Flux`/`ServerSentEvent`/`WebClient`，不切 Netty 容器）。Spring Boot 双栈共存时 Servlet 优先。

**理由**：
- 虚拟线程是 Servlet 栈的 Java 21 卖点（IO 密集吞吐）
- `Flux<ServerSentEvent>` 在 Spring MVC 下可正常 SSE 流式
- common 里基于 servlet 的 `GlobalExceptionHandler` 直接复用，零迁移成本
- 简历亮点：虚拟线程 + 传统 MVC，避免全栈响应式的学习与维护成本

### 2. LLM 网关为所有模型调用的唯一出口

工作流与 Agent 的每次 LLM 调用都经网关，统一享受限流/配额/熔断/计费/缓存治理。这样治理能力集中、可观测、可计费。

### 3. Provider 热插拔

`LlmProvider` 接口 + `ProviderRouter` 按 `supports(model)` 路由。P1 用 Mock，后续真实模型（OpenAI 兼容）只加一个 `@Component` 实现接口，核心零改动（OCP）。

## 分阶段里程碑

| 阶段 | 产出 | 状态 |
|------|------|------|
| P0 | 脚手架 + 依赖栈 + CI | ✅ |
| P1 | LLM 网关 MVP：Provider 适配 + 限流 + 配额 + SSE 透传 | 🚧 |
| P2 | 语义缓存 + 熔断降级 + 计费对账 | ⏳ |
| P3 | 工作流引擎：DAG + 状态机 + 事件溯源 + Saga | ⏳ |
| P4 | Agent 编排：ReAct + 多 Agent + Human-in-loop | ⏳ |
