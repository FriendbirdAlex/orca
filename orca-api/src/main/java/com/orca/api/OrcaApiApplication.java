package com.orca.api;

import org.redisson.spring.starter.RedissonAutoConfigurationV2;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * Orca 接入层启动类。
 * ComponentScan 覆盖 com.orca 全包, 聚合 gateway/workflow/agent/common 各模块 Bean。
 *
 * P2 已接入 Kafka(异步记账): 放开 KafkaAutoConfiguration。
 * 仅排除 RedissonAutoConfigurationV2(改用显式 RedissonConfig 建 RedissonClient)。
 */
@SpringBootApplication(exclude = {
        RedissonAutoConfigurationV2.class
})
@ComponentScan(basePackages = "com.orca")
public class OrcaApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrcaApiApplication.class, args);
    }
}
