# Orca 面试问答预案

> 围绕项目设计决策整理的高频面试题，每条对应项目里的真实实现。

## 限流

**Q: 为什么用 Redis+Lua 而不是单机令牌桶？**
多实例需全局视图；Lua 保证"查+扣"原子（Redis 单线程 + 脚本不可拆分），对比"先 GET 再 SET"的竞态窗口。

**Q: 令牌桶 vs 漏桶 vs 固定窗口？**
- 令牌桶支持突发（桶可瞬间消费到 capacity），贴 LLM 场景
- 漏桶匀速输出，不支持突发
- 固定窗口有临界突发问题；滑动窗口精确但内存高

**Q: Lua 脚本里为什么用 Redis TIME 而不是客户端时间？**
多机时钟漂移会导致令牌计算偏差，用服务端时间统一基准。

**Q: EVALSHA 是什么？**
Redis 用脚本 SHA 缓存，DefaultRedisScript 启动时加载，运行时用 EVALSHA 避免每次传全文，降网络开销。

## 配额

**Q: 配额扣减并发安全/会不会超卖？**
Redis Lua 原子预扣保证不超卖；调用成功异步落 DB，失败回滚 Redis；定时对账修正漂移。本项目 Redis 是热路径，DB（tenant_quota）是源记录。

**Q: 预扣+结算模型为什么这样设计？**
流式无法预知 token 数，预扣 `prompt+maxTokens` 上界，完成后用 Provider 返回的真实 usage 退回多余。预扣偏大只浪费预留额度，不影响正确性。

**Q: 最终一致性怎么保证？**
refund 后同步 upsert tenant_quota（INSERT ON DUPLICATE KEY UPDATE）；Redis 故障丢数据时按 DB 重建；DB 写失败不阻塞主流程（已扣 Redis），靠对账补偿。

## SSE / 流式

**Q: SSE 背压怎么处理？**
Reactor 的 request 信号 + Servlet 非阻塞写缓冲反压；下游慢则向上游施加反压。Mock 用 delayElements 节流是最简背压。

**Q: 为什么流式不用 List 一次性返回？**
LLM 生成是逐 token 的，流式能首字延迟低、用户感知快；且长响应避免一次性占用大内存。

**Q: Flux<ServerSentEvent> 在 Spring MVC（Tomcat）下能工作吗？**
能。Spring MVC 支持返回 reactive 类型，容器是 Tomcat，WebFlux 作库用。双栈共存时 Servlet 优先。

## ThreadLocal / Reactor

**Q: 流式时为什么不能在 Flux 里读 TenantContext？**
Reactor 线程不继承 ThreadLocal。解决：返回 Flux 前在请求线程把 tenantId 显式捕获进闭包；末包 usage 用 AtomicReference 跨阶段透出，doOnComplete 读取结算。

**Q: 虚拟线程下 ThreadLocal 行为？**
每个虚拟线程独立副本，不跨虚拟线程；CarrierThread 不自动继承 InheritableThreadLocal。所以拦截器必须在 afterCompletion 清理，防线程复用串租户。

## 架构

**Q: 为什么选 Servlet + 虚拟线程而非全栈 WebFlux？**
虚拟线程是 Java 21 Servlet 栈卖点（IO 密集吞吐），避免响应式全栈的学习/维护成本；WebFlux 降级为库用 Flux/ServerSentEvent 即可。common 的 GlobalExceptionHandler 直接复用。

**Q: 为什么网关是所有 LLM 调用唯一出口？**
治理集中（限流/配额/熔断/计费/缓存）、可观测、可计费。工作流与 Agent 的每次 LLM 调用都经网关。

**Q: Provider 怎么热插拔？**
LlmProvider 接口 + ProviderRouter 按 supports(model) 路由。新增真实 Provider 只加一个 @Component 实现接口，核心零改动（OCP）。

## cache-aside

**Q: 缓存穿透/雪崩/击穿怎么防？**
- 穿透：不存在的 key 也缓存空值（60s）
- 雪崩：TTL 加随机抖动（±30s）
- 击穿：P1 单机不处理（概率低），P2 用 Redisson 锁保护重建

## 异常处理

**Q: 业务异常为什么用 RuntimeException？**
非受检，不强制 try-catch 污染调用栈；系统异常单独兜底。GlobalExceptionHandler 统一返回 Result，业务异常走 HTTP 200（body code 区分），系统异常走 500。

## 后续阶段（P3 工作流）

**Q: 为什么用事件溯源而不是直接 update 状态表？**
状态表是物化视图，event 是真相源；可重放重建任意时刻状态；审计/调试/补偿都依赖历史。

**Q: Saga 补偿为什么不用 2PC/TCC？**
LLM 是第三方不可逆资源，无 prepare 阶段，2PC 不适用；TCC 需对端支持 try/confirm/cancel，模型 API 不支持。Saga 最终一致、对端无侵入。
