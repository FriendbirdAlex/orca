# P1 — LLM 网关 MVP 设计

## 目标

把 `orca-gateway` 从全空骨架做成真实可调用的 LLM 网关，覆盖面试高频考点（限流/配额/SSE/鉴权），用 Mock Provider 起步，Provider 接口设计为热插拔，后续真实模型零改动接入。

## 核心架构决策

- **Servlet + 虚拟线程**：Tomcat 容器，WebFlux 降级为库（Flux/ServerSentEvent）。虚拟线程是 Java 21 卖点。
- **P1 同时接 MySQL + Redis**：鉴权/记账/配额源记录用 MySQL；限流/配额热计数用 Redis。Kafka 留 P2 异步记账。
- **令牌桶限流**：Redis + Lua，单 key HMSET 存 tokens+ts。
- **预扣+结算**：rpm 预扣1不退；tpm/quota 预扣上界、完成后退回多余。

## 调用链路（同步 chat）

```
请求 → Tomcat(虚拟线程) → AuthInterceptor.preHandle
  ├─ 无 X-API-Key           → UNAUTHORIZED(100401)
  ├─ TenantService.resolve  (Redis cache-aside → DB api_key join tenant)
  │    ├─ 找不到             → UNAUTHORIZED
  │    └─ tenant.status=0   → FORBIDDEN
  └─ TenantContext.set(TenantInfo)
→ ChatController → ChatService.chat
  1) rateLimiter.tryConsume(RPM,1)        失败 → GW_LIMITED(200001)
  2) rateLimiter.tryConsume(TPM,maxTokens) 预扣  失败 → GW_LIMITED
  3) quotaManager.reserve(prompt+maxTokens) 失败 → GW_QUOTA_EXCEEDED(200002), 退回 tpm
  4) router.route(model)                   无 Provider → GW_NO_PROVIDER(200004), 退回全部
  5) provider.chat                         异常 → GW_UPSTREAM_ERROR(200500), 全额退回 + call_log(fail)
  6) settle 退回多余 tpm/quota + upsert tenant_quota
  7) call_log insert
  8) return Result.success
→ afterCompletion: TenantContext.clear()
```

SSE 链路差异：步骤 1-4 同步段完成（请求线程，返回 Flux 之前）；步骤 5+ Reactor 线程推送，`tenantId` 闭包显式捕获（Reactor 线程不继承 ThreadLocal）；末包 usage 用 AtomicReference 透出，`doOnComplete` 结算；`onErrorResume` 发 error 事件 + 全额退回。

## 库表

详见 `deploy/docker/init/mysql/02-gateway-p1.sql`：
- `tenant` 扩展：rpm_limit / tpm_limit / daily_quota
- `api_key`：租户凭证（P1 明文，生产存 hash）
- `tenant_quota`：每日配额源记录（UK tenant+date）
- `call_log`：调用流水（idx tenant+time）

## 类结构

### orca-common.context（跨模块共享）
- `TenantInfo` record：租户信息
- `TenantContext`：ThreadLocal 持有者，require/get/clear

### orca-api（接入层）
- `interceptor.AuthInterceptor`：解析 X-API-Key → TenantContext
- `config.WebConfig`：注册拦截器，拦 /v1/**

### orca-gateway 九包
- **provider**：`LlmProvider` 接口 + `MockLlmProvider` + `TokenEstimator` + `model/{ChatRequest,ChatResponse,ChatChunk}`
- **router**：`ProviderRouter` + `DefaultProviderRouter`（按 supports 路由）
- **limiter**：`RateLimiter` + `RedisRateLimiter` + `token_bucket.lua`/`token_refund.lua`
- **quota**：`QuotaManager` + `RedisQuotaManager` + `quota_prededuct.lua`/`quota_refund.lua` + `model.TenantQuota`/`mapper.TenantQuotaMapper`
- **billing**：`CallLog` + `CallLogMapper` + `CallLogService`（P1 同步写）
- **service**：`TenantService`(cache-aside) + `ChatService`(编排核心) + entity/mapper
- **controller**：`ChatController`（同步 + SSE）
- **config**：`GatewayProperties`/`RedisConfig`/`MyBatisPlusConfig`/`LuaScriptConfig`
- **cache**：P1 留空（P2 语义缓存）

## 预扣/结算模型

| 维度 | 预扣 | 结算退回 | 失败处理 |
|------|------|---------|---------|
| rpm | 1 | 不退 | - |
| tpm | maxTokens（上界） | maxTokens - actualCompletion | 全额退回 |
| quota | prompt + maxTokens | reserve - actualTotal | 全额退回 |

预扣偏大只浪费预留额度，不影响正确性（真实 token 完成后用 usage 结算退回）。

## 面试考点映射

| 组件 | 面试高频题 |
|------|-----------|
| 令牌桶 Lua | 令牌桶 vs 漏桶/固定窗口；Redis+Lua 原子性；TIME 防时钟漂移；EVALSHA 缓存 |
| quota 预扣+结算 | 并发安全防超卖；最终一致(Redis热路径+DB源)；幂等(requestId) |
| SSE+Flux | 背压(Reactor request 信号)；流式为何不用 List；Flux 在 Spring MVC 下工作 |
| ThreadLocal+Reactor | 不跨线程；虚拟线程下行为；显式捕获+AtomicReference 方案 |
| Web+WebFlux 共存 | 双栈优先级(Servlet wins)；为何选 Servlet(虚拟线程) |
| cache-aside | 穿透(空值缓存)/雪崩(TTL随机)/击穿(P2锁) |
| 虚拟线程 | IO 密集吞吐；不适用 CPU 密集；与 Reactor 分工 |

## P1 边界（不做，留 P2）

语义缓存 / 熔断降级 / 真实 Provider / Kafka 异步记账 / 计费对账 / 多 Provider 加权路由 / API Key 哈希存储 / 请求重试超时。

## 验证

```bash
docker compose -f deploy/docker/docker-compose.yml up -d mysql redis
mvn -B clean install -DskipTests && cd orca-api && mvn spring-boot:run
# 同步
curl -X POST http://localhost:8080/v1/chat/completions -H "X-API-Key: sk-orca-demo-0001" -H "Content-Type: application/json" -d '{"model":"mock","messages":[{"role":"user","content":"hello"}],"max_tokens":100}'
# 流式
curl -N -X POST "http://localhost:8080/v1/chat/completions?stream=true" -H "X-API-Key: sk-orca-demo-0001" -H "Content-Type: application/json" -d '{"model":"mock","messages":[{"role":"user","content":"hello"}],"max_tokens":100}'
```
