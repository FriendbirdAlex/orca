-- 令牌桶限流: 查+扣原子(单线程 Redis + Lua 脚本不可拆分)
-- 面试点:
--   1. 用 Redis TIME (服务端时间) 而非客户端时间, 避免多机时钟漂移导致令牌计算偏差
--   2. 单 key HMSET 存 {tokens, ts}, 原子读改写, 对比"先 GET 再 SET"的竞态窗口
--   3. 令牌桶 vs 漏桶: 令牌桶支持突发(桶可瞬间消费到 capacity), 更贴 LLM 场景
--
-- KEYS[1] = orca:rl:{RPM|TPM}:{tenantId}
-- ARGV[1] = capacity        桶容量
-- ARGV[2] = refillRate       每秒补充令牌数 (capacity/60)
-- ARGV[3] = requested        本次请求消耗令牌数

local capacity   = tonumber(ARGV[1])
local refillRate = tonumber(ARGV[2])
local requested  = tonumber(ARGV[3])

-- Redis 服务端时间(秒+微秒), 毫秒
local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)

local d = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(d[1])
local ts = tonumber(d[2])
if tokens == nil then tokens = capacity end       -- 首次: 满桶
if ts == nil then ts = now end

-- 按经过时间补充令牌, 封顶 capacity
local filled = tokens + math.max(0, now - ts) / 1000.0 * refillRate
if filled > capacity then filled = capacity end

local allowed = 0
local remaining = filled
if filled >= requested then
    remaining = filled - requested
    allowed = 1
end

redis.call('HMSET', KEYS[1], 'tokens', tostring(remaining), 'ts', tostring(now))
redis.call('EXPIRE', KEYS[1], 300)   -- 5min 无活动过期, 回收内存

return {allowed, math.floor(remaining)}
