-- 配额原子预扣: 防超卖(并发安全核心)
-- 面试点:
--   1. 并发场景下"先查 consumed 再判断再写"会竞态, Lua 原子保证不超卖
--   2. Redis 热路径(高频读写) + DB tenant_quota 作源记录(最终一致), Redis 丢数据按 DB 重建
--   3. key 按天 orca:quota:{tenant}:{date}, TTL>24h, 跨天自动新桶
--
-- KEYS[1] = orca:quota:{tenantId}:{yyyyMMdd}
-- ARGV[1] = dailyLimit    每日配额上限
-- ARGV[2] = requested     本次预扣(预扣上界 prompt+maxTokens)
-- ARGV[3] = ttl           过期秒数(>24h)

local limit = tonumber(ARGV[1])
local req = tonumber(ARGV[2])
local ttl = tonumber(ARGV[3])

local consumed = tonumber(redis.call('HGET', KEYS[1], 'consumed'))
if consumed == nil then consumed = 0 end

local allowed = 0
local remaining = limit - consumed
if consumed + req <= limit then
    consumed = consumed + req
    allowed = 1
    remaining = limit - consumed
end

redis.call('HMSET', KEYS[1], 'consumed', tostring(consumed), 'limit', tostring(limit))
redis.call('EXPIRE', KEYS[1], ttl)
return {allowed, remaining}
