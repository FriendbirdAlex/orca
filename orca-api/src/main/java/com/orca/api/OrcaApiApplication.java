package com.orca.api;

import org.redisson.spring.starter.RedissonAutoConfigurationV2;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * Orca 接入层启动类。
 * ComponentScan 覆盖 com.orca 全包, 聚合 gateway/workflow/agent/common 各模块 Bean。
 *
 * P1 已接入 MySQL + Redis: 放开 DataSource/Redis 自动配置。
 * 排除项:
 *  - KafkaAutoConfiguration: 异步记账留 P2
 *  - RedissonAutoConfigurationV2: 其对 spring.data.redis.password 读取行为不稳定,
 *    且启动时强校验连接易致启动失败。改用显式 RedissonConfig(gateway.config) 建 RedissonClient。
 *    (P1 限流/配额走 StringRedisTemplate+Lua, Redisson 为 P2 分布式锁/熔断预留)
 */
@SpringBootApplication(exclude = {
        KafkaAutoConfiguration.class,
        RedissonAutoConfigurationV2.class
})
@ComponentScan(basePackages = "com.orca")
public class OrcaApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrcaApiApplication.class, args);
    }
}
