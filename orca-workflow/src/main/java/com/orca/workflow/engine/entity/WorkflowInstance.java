package com.orca.workflow.engine.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工作流实例(运行态)。
 * tenant_snapshot: 创建时租户快照 JSON, 调度/异步线程重建 TenantContext(核心矛盾解法)。
 * next_schedule_at: 调度器扫描 + 退避用。
 */
@Data
@TableName("workflow_instance")
public class WorkflowInstance {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String workflowCode;

    private Integer version;

    private String status;          // RUNNING/PAUSED/SUCCEEDED/FAILED

    private String triggerType;     // API/SCHEDULE

    private String input;           // JSON 启动入参

    private String context;         // JSON 运行上下文(节点输出)

    private String bizId;           // 业务幂等键

    private Long tenantId;

    private String tenantSnapshot;  // JSON 租户快照

    private LocalDateTime nextScheduleAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
