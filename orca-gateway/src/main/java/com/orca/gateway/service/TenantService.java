package com.orca.gateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.orca.common.context.TenantInfo;
import com.orca.common.exception.BizException;
import com.orca.common.exception.ErrorCode;
import com.orca.gateway.service.entity.ApiKey;
import com.orca.gateway.service.entity.Tenant;
import com.orca.gateway.service.mapper.ApiKeyMapper;
import com.orca.gateway.service.mapper.TenantMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 租户鉴权服务: apiKey → TenantInfo。
 *
 * 面试点(cache-aside + 三防):
 *  1. 缓存穿透: 不存在的 apiKey 也缓存空值(60s), 避免恶意 key 打穿 DB
 *  2. 缓存雪崩: TTL 加随机抖动(±30s), 防止同时过期
 *  3. 缓存击穿: P1 单机不处理(概率低); P2 用 Redisson 锁保护重建
 *  4. 命中时异步更新 last_used_at(P1 简化省略, P2 补)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantService {

    private static final String CACHE_KEY = "orca:apikey:";
    private static final String NULL_HOLDER = "__NULL__";
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);
    private static final Duration NULL_TTL = Duration.ofSeconds(60);

    private final ApiKeyMapper apiKeyMapper;
    private final TenantMapper tenantMapper;
    private final StringRedisTemplate redis;

    /**
     * 解析 API Key → 租户信息。失败抛 BizException。
     */
    public TenantInfo resolveByApiKey(String apiKey) {
        String cacheKey = CACHE_KEY + apiKey;
        String cached = redis.opsForValue().get(cacheKey);

        // 缓存命中空值 → 鉴权失败
        if (NULL_HOLDER.equals(cached)) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        // 缓存命中有效值 → 解析
        if (cached != null) {
            return parse(cached);
        }

        // 未命中: 查 DB
        ApiKey key = apiKeyMapper.selectOne(new LambdaQueryWrapper<ApiKey>()
                .eq(ApiKey::getApiKey, apiKey));
        if (key == null || key.getStatus() == null || key.getStatus() != 1) {
            // 防穿透: 缓存空值
            redis.opsForValue().set(cacheKey, NULL_HOLDER, jittered(NULL_TTL));
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }

        Tenant tenant = tenantMapper.selectById(key.getTenantId());
        if (tenant == null || tenant.getStatus() == null || tenant.getStatus() != 1) {
            redis.opsForValue().set(cacheKey, NULL_HOLDER, jittered(NULL_TTL));
            throw new BizException(ErrorCode.FORBIDDEN, "租户已禁用");
        }

        TenantInfo info = new TenantInfo(
                tenant.getId(), tenant.getTenantCode(), apiKey, key.getId(),
                tenant.getRpmLimit(), tenant.getTpmLimit(), tenant.getDailyQuota());

        // 写回缓存(带随机 TTL 防雪崩)
        redis.opsForValue().set(cacheKey, serialize(info), jittered(CACHE_TTL));
        return info;
    }

    /** 主动失效(改密钥/禁用租户时调用) */
    public void evict(String apiKey) {
        redis.delete(CACHE_KEY + apiKey);
    }

    private String serialize(TenantInfo info) {
        return info.tenantId() + "|" + info.tenantCode() + "|" + info.apiKeyId()
                + "|" + info.rpm() + "|" + info.tpm() + "|" + info.dailyQuota()
                + "|" + info.apiKey();
    }

    private TenantInfo parse(String s) {
        String[] p = s.split("\\|", -1);
        return new TenantInfo(
                Long.parseLong(p[0]), p[1], p[6],
                p[2].isEmpty() ? null : Long.parseLong(p[2]),
                Integer.parseInt(p[3]), Integer.parseInt(p[4]), Long.parseLong(p[5]));
    }

    /** TTL 加 ±30s 抖动防雪崩 */
    private Duration jittered(Duration base) {
        long jitter = ThreadLocalRandom.current().nextLong(-30, 31);
        return base.plusSeconds(jitter);
    }
}
