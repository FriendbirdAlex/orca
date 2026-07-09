package com.orca.workflow.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 工作流配置, 绑定 orca.workflow.*。
 */
@Data
@Component
@ConfigurationProperties(prefix = "orca.workflow")
public class WorkflowProperties {

    /** 实例调度扫描间隔(ms) */
    private long scanIntervalMs = 3000;

    /** 人工审批超时扫描间隔(ms) */
    private long humanTaskScanIntervalMs = 10000;

    /** 分布式锁等待(ms) */
    private long lockWaitMs = 500;

    /** 调度失败退避(ms), 防坏实例空转 */
    private long backoffMs = 5000;

    /** Saga 补偿最大重试 */
    private int sagaMaxRetry = 3;

    /** 人工审批默认超时(分钟) */
    private int humanTaskDefaultTimeoutMinutes = 60;

    /** HTTP 节点连接/读取超时(ms) */
    private long httpConnectTimeoutMs = 3000;
    private long httpReadTimeoutMs = 5000;

    /** 调度器一次拉取实例数(LIMIT) */
    private int schedulerBatchSize = 100;
}
