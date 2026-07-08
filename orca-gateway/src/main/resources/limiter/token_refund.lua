-- 令牌桶结算退回(流式/同步完成后, 把预扣多余的 tpm 令牌退回)
-- 退回值封顶 capacity, 防止溢出
--
-- KEYS[1] = orca:rl:{RPM|TPM}:{tenantId}
-- ARGV[1] = capacity
-- ARGV[2] = refund

local capacity = tonumber(ARGV[1])
local refund = tonumber(ARGV[2])

local tokens = tonumber((redis.call('HMGET', KEYS[1], 'tokens'))[1])
if tokens == nil then tokens = capacity end

tokens = math.min(capacity, tokens + refund)

redis.call('HSET', KEYS[1], 'tokens', tostring(tokens))
return math.floor(tokens)
