# P2 — 网关纵深设计（熔断降级 + Kafka 异步记账 + 语义缓存 + 计费对账）

## 目标

在 P1 网关 MVP 基础上，加四块纵深治理能力，覆盖面试高频考点：熔断状态机、Kafka 削峰+幂等消费、向量检索语义缓存、最终一致对账。Mock embedding 跑通语义缓存链路（生产换真实 embedding 零改动）。

## 模块一：熔断降级（Resilience4j）

**设计**：装饰器 `CircuitBreakerLlmProvider` 包在 `DefaultProviderRouter.route()` 返回的 Provider 外，每个 Provider name 一个熔断器。

- 同步 chat：`cb.executeSupplier(() -> delegate.chat(req))`，CallNotPermittedException → `BizException(GW_CIRCUIT_OPEN)`
- 流式 streamChat：同步 `cb.tryAcquirePermission()`（false→抛），返回 `delegate.streamChat(req).doOnComplete(cb::onSuccess).doOnError(cb::onError)`
- 熔断开启时 ChatService 按失败语义全额退回已扣额度
- `ignore BizException`：限流/配额/熔断是业务决策，不计入熔断统计，只记上游故障

**面试点**：CLOSED/OPEN/HALF_OPEN 状态机；为何不引 resilience4j-reactor（手动回调更轻）；熔断 vs 限流区别。

## 模块二：Kafka 异步记账

**设计**：topic `orca-call-log`，key=tenantId（同租户同分区有序），value=CallLog JSON。`CallLogService.record` 接口不变，内部从同步 insert 改 Kafka send；`CallLogConsumer` 幂等消费写 DB。

- 幂等：`insertIgnoreOnDuplicate`（ON DUPLICATE KEY UPDATE id=id）+ call_log `uk_request_id` 唯一索引，解决 at-least-once 重复消费
- 削峰：生产端高写先入 Kafka，消费端 concurrency=3 平滑写 DB
- DLT：消费失败重试耗尽转死信队列
- ack-mode=record：按消息确认 offset

**面试点**：同步→异步削峰解耦；at-least-once+幂等消费 vs exactly-once；分区 key 保序；DLT 死信。

## 模块三：语义缓存（Mock embedding + PgVector）

**设计**：同步 chat 限流后、配额前查缓存，命中不扣配额/不调 Provider 直接返回（cached=true）；未命中正常后写缓存。双路命中：

1. **精确快路径**：`prompt_hash`（SHA-256）索引命中
2. **语义路径**：向量 ANN `ORDER BY embedding <=> ?::vector LIMIT 1`，sim=1-distance ≥ 0.92 命中

- `MockEmbeddingProvider`：词袋+哈希 384 维向量，生产换真实 embedding 实现接口即可
- PgVector 第二数据源：`orca.pgvector.*`，**只暴露 JdbcTemplate bean 不注册 DataSource**，避免触发 MySQL 自动配置退避
- 向量参数用 `?::vector` 字符串 cast，无需 pgvector Java 类型处理器
- 幂等写：PG `ON CONFLICT (tenant_id, prompt_hash) DO UPDATE`
- ivfflat ANN 索引（余弦距离）

**面试点**：语义缓存 vs 精确缓存；`<=>` 余弦距离 + ivfflat ANN；双路命中；第二数据源为何不注册 DataSource bean（避免 DataSourceAutoConfiguration 退避）；缓存一致性（TTL+幂等）。

## 模块四：计费对账

**设计**：`@Scheduled` 每日 2:10 跑，聚合 call_log（按 tenant+date）vs tenant_quota.consumed_tokens 两方对账，差异告警 + 生成 billing_record。

- 三方数据源：call_log 聚合（实际用量）/ tenant_quota（配额持久化值）/ Redis 热值（best-effort，key TTL 25h 可能过期）
- 主对比：call_log vs tenant_quota（Redis 侧以 tenant_quota 为代表）
- 计费金额 = totalTokens/1000 * perKTokens
- 差异超阈（100 token）→ diff_flag=1 + 告警日志
- 幂等 upsert billing_record（uk_tenant_period）

**面试点**：最终一致对账；为何以 tenant_quota 为 Redis 侧代表（key TTL 短）；@Scheduled+@EnableScheduling；差异告警阈值。

## P2 踩坑（面试可聊）

1. **PgVectorConfig @PostConstruct 自循环**：`ensureExtension` 调 `@Bean` 方法触发 registry 循环 → 去掉 @PostConstruct，向量扩展由容器 init SQL 保证
2. **Resilience4jConfig @PostConstruct 自循环**：`registerBreakers` 调 `circuitBreakerRegistry()` → 改在 @Bean 方法内注册
3. **@MapperScan 把业务接口当 Mapper**：`@MapperScan("com.orca.gateway")` 扫到 `LlmProvider` 等接口，MyBatis 建代理混入 `List<LlmProvider>` 注入，调 `.name()` 报 "Invalid bound statement" → 加 `annotationClass = Mapper.class` 只注册标 @Mapper 的接口
4. **HikariDataSource 连不上阻塞启动**：PgVector 未起时 minimumIdle=1 触发连接失败 → minimumIdle=0 + initializationFailTimeout=-1 允许后启动
5. **Kafka/PgVector 镜像源限流**：docker.xuanyuan.me 429，代码降级设计保证应用不崩（Kafka producer 懒连接、语义缓存查询失败降级）

## 验证（已通过）

- 应用启动 ✅（Kafka/PgVector 未起时降级运行不崩）
- 同步 chat ✅ `code=0 cached=False usage={...}`（缓存降级跳过、Kafka 发送失败降级、主流程未阻塞）
- 对账接口 ✅ `reports=1 alerts=1`，billing_record 落库（total_tokens=332, quota_consumed=68, diff_flag=1, billing_amount=0.0007）
- 差异检测 ✅ call_log 聚合 vs tenant_quota 差异告警生效
- Kafka 异步记账 ✅ 间接验证（call_log 近5分钟 0 条，证明走 Kafka 未同步写 DB）

## P2 边界（不做）

真实 embedding API / 多 Provider 加权故障转移 / 账单导出 / 流式语义缓存 / Kafka exactly-once / 实时对账 / ivfflat 调优。

## 待验证（受镜像源限流阻塞）

- Kafka 消费者实际写 call_log（需 Kafka 容器）
- 语义缓存命中（需 PgVector 容器 + llm_cache 表）
- 熔断 OPEN→HALF_OPEN 状态转换（需触发 Provider 失败）
