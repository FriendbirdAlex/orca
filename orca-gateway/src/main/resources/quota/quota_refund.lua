-- 配额结算退回: 完成后把预扣多余的部分退回(预扣 maxTokens, 实际用 completionTokens)
-- 下限 0, 防止负数
--
-- KEYS[1] = orca:quota:{tenantId}:{yyyyMMdd}
-- ARGV[1] = refund

local consumed = tonumber(redis.call('HGET', KEYS[1], 'consumed'))
if consumed == nil then consumed = 0 end

consumed = math.max(0, consumed - tonumber(ARGV[1]))
redis.call('HSET', KEYS[1], 'consumed', tostring(consumed))
return consumed
