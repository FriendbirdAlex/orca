package com.orca.gateway.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson 显式配置。
 *
 * 为何不依赖 RedissonAutoConfigurationV2 自动建?
 *  → 不同版本 Redisson starter 对 spring.data.redis.password 的读取行为不一致(尤其密码场景),
 *    自动配置可能漏掉 password 导致 AUTH failed 或连接被拒。
 *  → 显式建 RedissonClient 用 spring.data.redis.* 的值, 行为确定。
 *
 * 注: P1 实际未直接用 RedissonClient(限流/配额走 StringRedisTemplate + Lua),
 *     但 RedissonAutoConfigurationV2 会被 spring-boot-starter-data-redis 触发自动建 client 并校验连接,
 *     连不上则启动失败。显式建一个能连上的 client 排除此问题, 也为 P2(分布式锁/熔断)预留。
 */
@Configuration
public class RedissonConfig {

    @Value("${spring.data.redis.host}")
    private String host;

    @Value("${spring.data.redis.port}")
    private int port;

    @Value("${spring.data.redis.password:}")
    private String password;

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();
        var single = config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                .setConnectionPoolSize(16)
                .setConnectionMinimumIdleSize(2)
                .setConnectTimeout(3000)
                .setTimeout(3000);
        if (password != null && !password.isBlank()) {
            single.setPassword(password);
        }
        return Redisson.create(config);
    }
}
