# Orca · AI 工作流编排平台

> 面向后端的 AI 工作流编排平台 = **轻量 Temporal（编排）+ LLM 网关（治理）+ Agent runtime（推理）**

三层不是拼凑：网关是所有 LLM 调用的唯一出口，Agent 是工作流的一等节点类型，引擎为 Agent 提供调度 / 重试 / 状态机骨架。本项目为个人秋招简历项目，从 0 自建并部署上线，覆盖后端面试常见技术点与常用中间件。

## 架构

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

## 模块

| 模块 | 职责 | 关键技术点 |
|------|------|-----------|
| `orca-api` | REST 接口、SSE 推送、API Key 鉴权 | Spring WebFlux、SSE、虚拟线程 |
| `orca-workflow` | DSL 解析、DAG、状态机、调度、Saga、事件溯源 | 状态机、分布式锁、延迟队列 |
| `orca-agent` | ReAct 循环、Tool 注册、短期记忆 | Tool Calling、向量检索 |
| `orca-gateway` | Provider 适配、路由、限流、配额、缓存、熔断、计费 | Redis+Lua、熔断器、Reactor |
| `orca-common` | DTO、异常、全局处理 | 统一返回 |
| `orca-observability` | Trace / Metrics | OpenTelemetry、Prometheus |

## 技术栈

Java 21 · Spring Boot 3.2 · MySQL 8 · Redis 7 · Kafka 3 (KRaft) · Elasticsearch 8 · PgVector · MyBatis-Plus · Redisson · Resilience4j · OpenTelemetry · Prometheus

## 快速开始

### 1. 环境要求

- JDK 21
- Maven 3.9+
- Docker & Docker Compose

### 2. 拉起依赖栈

```bash
docker compose -f deploy/docker/docker-compose.yml up -d
```

### 3. 构建运行

```bash
mvn -B clean install -DskipTests
cd orca-api && mvn spring-boot:run
```

### 4. 验证

```bash
curl http://localhost:8080/api/health
curl http://localhost:8080/actuator/health
```

## 分阶段里程碑

| 阶段 | 产出 | 状态 |
|------|------|------|
| P0 | 脚手架 + 依赖栈 + CI | ✅ |
| P1 | LLM 网关 MVP：Provider 适配 + 限流 + 配额 + SSE 透传 | ✅ |
| P2 | 语义缓存 + 熔断降级 + Kafka 异步记账 + 计费对账 | ✅ |
| P3 | 工作流引擎：DAG + 状态机 + 事件溯源 + Saga | ⏳ |
| P4 | Agent 编排：ReAct + 多 Agent + Human-in-loop | ⏳ |

## License

MIT
