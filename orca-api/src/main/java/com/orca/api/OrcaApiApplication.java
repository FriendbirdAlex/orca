package com.orca.api;

import org.redisson.spring.starter.RedissonAutoConfigurationV2;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * Orca 接入层启动类。
 * ComponentScan 覆盖 com.orca 全包, 聚合 gateway/workflow/agent/common 各模块 Bean。
 *
 * 排除 DataSource/Redis/Kafka/Redisson 自动配置: P0/P1 脚手架阶段尚未接中间件,
 * 待 P1 网关真正连 Redis/MySQL 时, 按需在 application.yml 启用并移除对应排除项。
 * 注: Redisson 用独立自动配置类 RedissonAutoConfigurationV2, 不在 Spring 的 RedisAutoConfiguration 内, 需单独排除。
 */
@SpringBootApplication(exclude = {
        DataSourceAutoConfiguration.class,
        RedisAutoConfiguration.class,
        KafkaAutoConfiguration.class,
        RedissonAutoConfigurationV2.class
})
@ComponentScan(basePackages = "com.orca")
public class OrcaApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrcaApiApplication.class, args);
    }
}
