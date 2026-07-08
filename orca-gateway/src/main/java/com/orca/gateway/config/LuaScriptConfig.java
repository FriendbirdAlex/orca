package com.orca.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;

import java.util.List;

/**
 * Lua 脚本装载: 4 个脚本 bean, 启动时加载, 运行时复用(SHA 缓存)。
 *
 * 面考点: DefaultRedisScript 会被 Redis 用 EVALSHA 缓存脚本, 避免每次传全文, 降网络开销。
 */
@Configuration
public class LuaScriptConfig {

    @Value("classpath:limiter/token_bucket.lua")
    private Resource tokenBucketRes;

    @Value("classpath:limiter/token_refund.lua")
    private Resource tokenRefundRes;

    @Value("classpath:quota/quota_prededuct.lua")
    private Resource quotaPredeductRes;

    @Value("classpath:quota/quota_refund.lua")
    private Resource quotaRefundRes;

    @Bean
    public DefaultRedisScript<List> tokenBucketScript() {
        DefaultRedisScript<List> s = new DefaultRedisScript<>();
        s.setScriptSource(new ResourceScriptSource(tokenBucketRes));
        s.setResultType(List.class);
        return s;
    }

    @Bean
    public DefaultRedisScript<Long> tokenRefundScript() {
        DefaultRedisScript<Long> s = new DefaultRedisScript<>();
        s.setScriptSource(new ResourceScriptSource(tokenRefundRes));
        s.setResultType(Long.class);
        return s;
    }

    @Bean
    public DefaultRedisScript<List> quotaPredeductScript() {
        DefaultRedisScript<List> s = new DefaultRedisScript<>();
        s.setScriptSource(new ResourceScriptSource(quotaPredeductRes));
        s.setResultType(List.class);
        return s;
    }

    @Bean
    public DefaultRedisScript<Long> quotaRefundScript() {
        DefaultRedisScript<Long> s = new DefaultRedisScript<>();
        s.setScriptSource(new ResourceScriptSource(quotaRefundRes));
        s.setResultType(Long.class);
        return s;
    }
}
