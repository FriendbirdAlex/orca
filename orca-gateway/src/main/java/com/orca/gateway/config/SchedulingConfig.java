package com.orca.gateway.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 启用 @Scheduled 定时任务(P2 计费对账每日跑)。
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
