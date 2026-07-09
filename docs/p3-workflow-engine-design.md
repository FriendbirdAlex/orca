# P3 — 工作流引擎设计（DAG + 状态机 + 事件溯源 + Saga + 调度 + 人工审批）

## 目标

在 `orca-workflow` 模块实现轻量 Temporal 级工作流引擎。工作流的 LLM 节点经网关 ChatService 调用（架构决策：网关为唯一出口，白嫖限流/配额/熔断/缓存/记账）。覆盖面试最硬核考点。

## 核心矛盾与解法：异步线程 + TenantContext

`ChatService.chat()` 首行 `TenantContext.require()`，但工作流调度/异步线程无 ThreadLocal。
**解法 = 租户快照重建**：Controller 线程捕获 TenantInfo → JSON 存 `workflow_instance.tenant_snapshot` → LlmNodeExecutor 执行前反序列化 `TenantContext.set()` → finally `clear()`。选快照而非每次查 DB（避免每节点多一次查询）。必须 try-finally clear（虚拟线程池复用防串租户）。

## 五张表（04-p3.sql）

- `workflow_definition`(code+version 版本化)
- `workflow_instance`(status/tenant_snapshot/next_schedule_at，UK biz_id 幂等)
- `node_instance`(UK instance+node 幂等)
- `workflow_event`(append-only 事件溯源，UK instance+seq 保证顺序)
- `human_task`(人工审批，IDX status+timeout)

## 类设计（10 包）

- **dsl**：WorkflowDefinition/Dsl/NodeDef/EdgeDef/NodeType + DslParser + DslValidator(**Kahn 拓扑判环**)
- **state**：InstanceStatus/NodeStatus + StateMachine(白名单转换防乱序)
- **engine**：WorkflowEngine(start/resume/advance/failAndCompensate) + DagExecutor(parseAndCache/initPending/executeBatch/findReadyNodes) + InstanceContext(节点 IO 传递+模板渲染)
- **scheduler**：WorkflowScheduler(@Scheduled 扫 RUNNING 实例 + Redisson 实例锁 + 人工超时扫描)
- **event**：EventType + EventStore(**REQUIRES_NEW 独立事务 append**，可选发 Kafka，replay 重放)
- **compensation**：SagaCompensator(失败→SUCCEEDED 节点逆序补偿→FAILED)
- **task**：NodeExecutor 接口 + LlmNodeExecutor(快照重建 TenantContext) + HttpNodeExecutor + HumanTaskNodeExecutor(暂停-恢复-超时)
- **config**：WorkflowMyBatisConfig(@MapperScan annotationClass=Mapper) + WorkflowProperties + RestTemplateConfig
- **controller**：WorkflowController(定义/启动/状态/审批/事件重放)

## 调用链路

**正常**：POST /instances(AuthInterceptor set TenantContext) → start(幂等biz_id→取def解析DSL→require快照→INSERT RUNNING→append STARTED→initPending→advance) → executeBatch(findReady→LlmNodeExecutor set TenantContext→ChatService.chat→clear→SUCCEEDED+事件→下一批) → 全SUCCEEDED→COMPLETED

**失败+Saga**：节点异常→FAILED+NODE_FAILED事件→SagaCompensator 逆序 compensate(LLM仅记录/HTTP调compensateUrl/HUMAN无)→COMPENSATED事件→FAILED

**人工审批**：遇HUMAN→建human_task+实例PAUSED+PAUSED事件→本轮结束→人/decide(APPROVED→HUMAN节点SUCCEEDED+resume继续；REJECTED→failAndCompensate Saga→FAILED)；超时→scheduler扫到→TIMEOUT→failAndCompensate→FAILED

## 面试考点映射

| 考点 | 落地 |
|------|------|
| 事件溯源 vs 状态表 | EventStore REQUIRES_NEW 不丢 + node_instance 双写 + replay 重放 |
| Saga vs 2PC/TCC | 长流程最终一致 + 逆序补偿，对比 2PC 阻塞、TCC 三套侵入 |
| DAG 拓扑排序 | DslValidator Kahn 判环 + DagExecutor.findReadyNodes 就绪判断 |
| 分布式锁选型 | Redisson 实例级锁非全局，tryLock 不阻塞扫描 |
| 调度防重防漏 | 锁+二次校验+uk幂等防重；退避+fixedDelay防漏防堆积 |
| 人工审批暂停-恢复 | PAUSED 状态 + human_task + 超时扫描 |
| 幂等 | biz_id UK + uk_inst_node |
| TenantContext 异步传递 | 快照 JSON 重建 + 显式 set/clear |
| 状态机 | 白名单转换防并发乱序 |
| 事件顺序 | uk_inst_seq + MAX+1 |

## P3 边界（不做）

条件分支/并行网关/子流程、版本灰度、可视化、动态路由、节点级重试退避、多租户定义隔离

## 验证（已通过）

| 验证项 | 结果 |
|--------|------|
| LLM 节点 TenantContext 快照重建 | ✅ 异步线程调 ChatService.chat 不抛 IllegalStateException，返回 Mock 内容 |
| 人工审批暂停-恢复 | ✅ HUMAN 节点→PAUSED→POST /decide APPROVED→SUCCEEDED |
| 事件溯源重放 | ✅ 8 事件按 seq 单调递增(STARTED→...→COMPLETED)，可重放 |
| 幂等(biz_id) | ✅ 同 bizId 再启动返回同 instanceId |
| Saga 补偿 | ✅ HTTP 500→n2 FAILED→逆序 COMPENSATED(n1)→实例 FAILED |
| DAG 拓扑/状态机 | ✅ DSL 校验(Kahn 判环)+状态转换正常 |

完整事件序列(人工审批实例)：`STARTED → NODE_STARTED(n1) → NODE_SUCCEEDED(n1) → NODE_STARTED(n2) → PAUSED → HUMAN_APPROVED → RESUMED → COMPLETED`
Saga 实例：`STARTED → NODE_STARTED(n1) → NODE_SUCCEEDED(n1) → NODE_STARTED(n2) → NODE_FAILED(n2) → COMPENSATED(n1) → COMPLETED(FAILED)`

## 踩坑（实现时已规避）

1. WorkflowMyBatisConfig 必须带 `annotationClass=Mapper.class`（否则 NodeExecutor 接口被当 Mapper）
2. 不重复建 MybatisPlusInterceptor bean（复用 gateway 的，否则冲突）
3. EventStore.append 用 REQUIRES_NEW 独立 bean（Spring 代理生效）
4. LlmNodeExecutor TenantContext.set/clear 必须 try-finally
5. WorkflowScheduler 用 fixedDelayString 非 fixedRate
6. DSL 存 String 交 DslParser 解析
7. HUMAN 节点 execute 返回 _paused，DagExecutor 检测后停止本轮不置 SUCCEEDED
8. REJECTED/超时走 failAndCompensate（非 advance，因节点已 FAILED 不在执行循环）
9. **start 去掉 @Transactional + 不同步 advance**：长事务包裹节点执行(LLM 调用慢/Kafka send 阻塞)导致 curl 卡死；改 start 只建实例，调度器统一推进(带锁防并发)
10. **persistContext 只更新 context 字段**：全量 updateById 会用内存旧状态覆盖 HUMAN 节点设置的 PAUSED 状态
11. **Kafka producer 配 max.block.ms=3000**：默认 60s，Kafka 不可用时 send 阻塞主流程；配短快速失败降级
12. **EventStore 同步发 Kafka 是性能瓶颈**：Kafka 不可用时每事件卡 3s(正确性不受影响，仅性能)；生产可改异步 send 或 Kafka 不可用时跳过
