# Orca 简历叙事 + 面试问答预案

> 面向秋招后端开发岗位。项目从 0 自建并部署上线，覆盖面试高频考点 + 常用中间件 + Agent。仓库：https://github.com/FriendbirdAlex/orca

---

## 一、简历项目描述（可直接用于简历）

### Orca · AI 工作流编排平台

**技术栈**：Java 21（虚拟线程）· Spring Boot 3.2 · MySQL 8 · Redis 7 · Kafka 3 (KRaft) · Elasticsearch 8 · PgVector · MyBatis-Plus · Redisson · Resilience4j · OpenTelemetry

**项目简介**：面向开发者的 AI 工作流编排平台，由 LLM 网关（治理）、工作流引擎（编排）、Agent runtime（推理）三层有机组成——网关是所有 LLM 调用的唯一出口，Agent 是工作流的一等节点，引擎为 Agent 提供调度/重试/状态机骨架。从 0 自建 Maven 多模块工程，Docker Compose 一键拉起依赖栈，GitHub Actions CI，分四阶段迭代交付。

**核心工作与亮点**：

- **LLM 网关治理**：设计 Redis+Lua 令牌桶限流（租户维度 rpm/tpm，服务端 TIME 防时钟漂移）、Lua 原子预扣 + 结算退回的配额管理（Redis 热路径 + DB 源记录最终一致）、Resilience4j 熔断装饰器（ignore 业务异常只记上游故障）、PgVector 语义缓存（双路命中：精确 hash + 向量 ANN）、Kafka 异步记账（at-least-once + requestId 幂等消费 + DLT）。OpenAI 风格 `/v1/chat/completions` 同步 + SSE 流式透传。
- **工作流引擎**：自研轻量 Temporal 级引擎，JSON DSL 解析 + Kahn 拓扑排序判环、白名单状态机、事件溯源（REQUIRES_NEW 独立事务保证事件不丢）、Saga 逆序补偿（对比 2PC/TCC）、Redisson 实例级分布式锁调度（防重防漏）、Human-in-loop 人工审批（暂停-恢复-超时）。LLM 节点经网关调用，复用全部治理能力。
- **Agent 编排**：实现 ReAct 循环（Thought→Action→Observation→Final），Tool 注册中心（OCP 热插拔）、Redis 短期记忆、防失控四闸（步数/成本/工具异常/超时）。Agent 作为工作流 AGENT 节点，多 Agent 经 DAG 编排协同。脚本化 ReAct 解耦 Mock 与真实模型，生产换真模型循环不动。
- **关键技术决策**：解决"异步线程 TenantContext 不传递"（租户快照 JSON 重建 + 显式 set/clear 防虚拟线程池复用串租户）；Servlet + 虚拟线程路线（WebFlux 降级为库）平衡吞吐与维护成本。

**工程实践**：Maven 六模块 + 规范化 commit；端到端验证（限流/配额/Saga/事件溯源/ReAct 均有真实 curl 验证）；踩坑记录（@MapperScan 误扫业务接口、循环依赖、Kafka producer 阻塞等）形成面试可讲的排查经验。

---

## 二、一页纸电梯介绍（面试开场用）

"这是一个 AI 工作流编排平台，我自己从 0 设计实现的。核心是三层：底层 LLM 网关做治理——限流、配额、熔断、语义缓存、异步记账，所有模型调用都走这；中层是自研的工作流引擎，类似一个轻量级 Temporal，有 DAG 调度、事件溯源、Saga 补偿、人工审批；上层是 Agent runtime，支持 ReAct 循环和多 Agent 协同，Agent 本身就是工作流的一种节点。技术栈是 Java 21 + Spring Boot 3.2，用到了 Redis、Kafka、MySQL、PgVector 这些中间件。整个项目分四个阶段迭代，每个阶段都能独立跑通验证。"

---

## 三、面试问答预案（按模块）

### 限流

**Q: 为什么用 Redis+Lua 而不是单机令牌桶？**
多实例需全局视图；Lua 保证"查+扣"原子（Redis 单线程 + 脚本不可拆分），对比"先 GET 再 SET"的竞态窗口。

**Q: 令牌桶 vs 漏桶 vs 滑动窗口？**
令牌桶支持突发（桶可瞬间消费到 capacity），贴 LLM 场景；漏桶匀速不支持突发；固定窗口有临界突发，滑动窗口精确但内存高。

**Q: Lua 脚本里为什么用 Redis TIME 而非客户端时间？**
多机时钟漂移会导致令牌计算偏差，用服务端时间统一基准。EVALSHA 缓存脚本降网络开销。

### 配额

**Q: 配额扣减并发安全/会不会超卖？**
Redis Lua 原子预扣保证不超卖；调用成功落 DB，失败回滚 Redis；定时对账修正漂移。Redis 是热路径，DB（tenant_quota）是源记录。

**Q: 预扣+结算模型为什么这样设计？**
流式无法预知 token 数，预扣 prompt+maxTokens 上界，完成后用 Provider 真实 usage 退回多余。预扣偏大只浪费预留额度，不影响正确性。

### 熔断

**Q: 熔断器状态机？**
CLOSED→错误率超阈→OPEN（快速失败）→等 waitDuration→HALF_OPEN（放 N 个试探）→成功率高→CLOSED，仍失败→OPEN。

**Q: 为什么 ignore BizException？**
限流/配额/熔断本身是业务决策，不是上游故障，不应计入熔断统计，只记真正的上游失败。

**Q: 流式怎么做熔断？**
不引 resilience4j-reactor，流式用同步 tryAcquirePermission 预检 + doOnComplete/doOnError 回灌结果，更轻更可控。

### Kafka 异步记账

**Q: 同步写→异步写的价值？**
主流程不阻塞，高写流量由 Kafka 削峰，消费端按 concurrency 平滑写 DB，解耦。

**Q: 怎么保证不丢不重？**
at-least-once（ack-mode=record 手动按消息确认）+ 幂等消费（call_log requestId 唯一索引 + ON DUPLICATE KEY UPDATE），重投递无副作用。不用 exactly-once（成本高，幂等消费足够）。DLT 处理消费失败。

**Q: 分区 key 为什么用 tenantId？**
同租户同分区，消费有序（同租户的 call_log 顺序写入）。

### 语义缓存

**Q: 语义缓存 vs 精确缓存？**
精确缓存只命中完全相同的 prompt；语义缓存用向量相似度，"北京天气"和"北京今天天气"也能命中，命中率大幅提升。

**Q: 双路命中怎么设计？**
先精确 hash（SHA-256）快路径，命中直接返回（索引快）；未命中走向量 ANN（ivfflat 余弦距离），sim≥0.92 命中。精确路径覆盖高频重复，语义路径覆盖相似变体。

**Q: 缓存一致性？**
TTL 过期 + ON CONFLICT 幂等写。命中不扣配额不调 Provider，降本提速。

**Q: 第二数据源为什么不注册 DataSource bean？**
Spring Boot 的 DataSourceAutoConfiguration 是 @ConditionalOnMissingBean(DataSource)，注册第二个 DataSource 会触发 MySQL 自动配置退避。只暴露 JdbcTemplate bean，MySQL 主数据源不受影响。

### 事件溯源

**Q: 为什么用事件溯源而不是直接 update 状态表？**
状态表只存当前态，丢失中间过程；事件 append-only 存所有变迁，可重放重建任意时刻状态、可审计、可回滚。状态表是物化视图供调度快速查询，事件表是真相源。

**Q: 事件怎么保证不丢？**
EventStore.append 用 REQUIRES_NEW 独立事务，即使主流程（节点执行）回滚，事件也落库。seq 用 MAX+1 + 唯一约束兜底保证顺序。

### Saga 补偿

**Q: Saga vs 2PC/TCC？**
2PC 强一致但协调者单点 + 阻塞，不适合长事务（LLM 调用秒级）；TCC 三套实现业务侵入大；Saga 正向执行 + 失败逆序补偿，最终一致，业务侵入小（只补一个 compensate 方法）。代价是中间状态可见（已发 LLM 无法收回，仅记录）。

**Q: 补偿失败怎么办？**
不中断，继续补偿其余节点（尽力而为），最终标记是否需人工介入。

### DAG / 调度

**Q: 怎么检测 DAG 有环？**
Kahn 拓扑排序：入度=0 入队 BFS，出队时后继入度-1，若遍历数 != 总节点数则有环。

**Q: 调度器怎么防重复/漏调度？**
防重：实例级 Redisson 锁（非全局，并行度高）+ 二次状态校验 + uk_inst_node 幂等。防漏：fixedDelay（非 fixedRate 防堆积）+ 失败 next_schedule_at 退避防坏实例空转。

**Q: 分布式锁为什么选 Redisson 实例级而非全局？**
实例级锁让不同工作流实例并行推进，吞吐高；全局锁串行化所有实例成为瓶颈。tryLock 不阻塞扫描，抢不到说明别的实例在推进，跳过。

### 人工审批

**Q: 暂停-恢复怎么实现？宕机了怎么办？**
暂停=状态落库 PAUSED + human_task，实例不驻留内存；恢复=幂等回调 + 状态校验（只能 PAUSED→RUNNING）+ 推进。宕机无影响，状态全在 DB，调度器扫 PAUSED+超时即可恢复。超时=独立定时任务扫 human_task PENDING+timeout_at 到期。

### Agent / ReAct

**Q: ReAct 循环怎么工作？**
每步：LLM 推理输出 Action（调哪个工具）→ 执行工具得 observation → observation 喂回 LLM 再推理 → 直到输出 Final Answer。Thought→Action→Observation 循环。

**Q: Mock 模型不能做工具调用决策怎么办？**
脚本化 ReAct：MockLlmProvider 加协议模式，按 system message 注入的步数/历史 observation 脚本输出 Action/Final。循环结构真实（工具/记忆/循环/上限都是真的），仅"推理"脚本化。生产换真模型按同格式输出即可，循环不动（OCP）。

**Q: 怎么防 Agent 失控？**
四道闸：步数上限（AGENT_LOOP_LIMIT）、成本累加（AGENT_COST_LIMIT）、工具异常喂回 LLM 自纠（AGENT_TOOL_ERROR）、单步超时（沿用网关熔断）。

**Q: 多 Agent 怎么协同？**
DAG 编排：AGENT 节点用 edges 串联，上游 output.finalAnswer 喂下游（模板渲染），merge 节点等所有上游 SUCCEEDED。复用工作流引擎，零新机制。

### 架构决策

**Q: 为什么选 Servlet + 虚拟线程而非全栈 WebFlux？**
虚拟线程是 Java 21 Servlet 栈卖点（IO 密集吞吐），避免响应式全栈的学习/维护成本；WebFlux 降级为库用 Flux/ServerSentEvent 做 SSE 即可。common 的全局异常处理器直接复用。

**Q: 为什么网关是所有 LLM 调用唯一出口？**
治理集中（限流/配额/熔断/缓存/计费）、可观测、可计费。工作流 LLM 节点和 Agent 每步推理都经网关。

**Q: TenantContext 异步线程怎么传递？**
ThreadLocal 不跨线程（Reactor 调度线程/虚拟线程都不继承）。解法：建实例时 Controller 线程捕获 TenantInfo → JSON 存 instance.tenant_snapshot → 节点执行前反序列化 set → finally clear（防虚拟线程池复用串租户）。

### 踩坑排查（体现工程能力）

**Q: 遇过什么难排查的问题？**
- `@MapperScan` 误扫业务接口：全包扫描把 LlmProvider/NodeExecutor 接口当 Mapper 注册代理，注入 List 时混入代理报"Invalid bound statement"。解法：annotationClass=Mapper.class 只注册标 @Mapper 的。
- 循环依赖：@PostConstruct 调 @Bean 方法、PgVectorConfig 初始化触发的自循环，改在 @Bean 方法内注册 / 去掉 @PostConstruct。
- Kafka producer 阻塞：默认 max.block.ms=60s，Kafka 不可用时 send 阻塞主流程，改 3s 快速失败降级。
- start 长事务卡死：@Transactional 包裹 LLM 调用 + Kafka send 阻塞致 curl 卡死，改 start 不加事务、调度器统一推进带锁防并发。
- persistContext 覆盖状态：全量 updateById 用内存旧状态覆盖了 HUMAN 节点设置的 PAUSED，改只更新 context 字段。

---

## 四、分阶段交付（体现迭代能力）

| 阶段 | 产出 | 可讲考点 |
|------|------|---------|
| P1 | LLM 网关 MVP（限流/配额/SSE/鉴权） | Redis+Lua、令牌桶、SSE 背压、cache-aside |
| P2 | 网关纵深（熔断/Kafka记账/语义缓存/对账） | 熔断状态机、Kafka 削峰幂等、向量检索、最终一致对账 |
| P3 | 工作流引擎（DAG/状态机/事件溯源/Saga/人工审批） | 拓扑排序、事件溯源、Saga、分布式锁、Human-in-loop |
| P4 | Agent 编排（ReAct/多Agent/短期记忆） | ReAct、Tool Calling、多 Agent 协同、防失控 |

每阶段独立可上线、可演示、可深聊，秋招前能停在任何阶段都不亏。

---

## 五、可量化/可演示点（面试加分）

- 端到端验证：每个模块都有真实 curl 验证（限流触发 200001、配额 200002、Saga COMPENSATED 事件、ReAct 2 步、事件溯源 8 事件重放）
- 事件溯源可重放：`GET /v1/workflow/instances/{id}/events` 返回完整事件序列
- 语义缓存命中：同 prompt 第二次 `cached=true`
- 踩坑→修复→可讲：每个坑都形成了"现象→根因→修复→面试讲法"的闭环

## 六、待完善（诚实说明）

- Kafka 异步记账端到端：代码实现完整（生产者+幂等消费者+DLT），受限于 apache/kafka 单机 KRaft 协调器配置（FIND_COORDINATOR 超时），生产者侧验证通过，消费者侧协调待环境修复
- 熔断状态机端到端：Resilience4j 装饰器代码完整，触发需失败注入开关（Mock Provider 不失败）
- 压测数字：未做，可补 JMeter 压测出 QPS/TPS 数字
- 真实模型：用 Mock Provider，接口已为真实 OpenAI 兼容 Provider 留好（OCP）
