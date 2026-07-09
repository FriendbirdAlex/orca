package com.orca.workflow.scheduler.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 人工审批任务(Human-in-loop)。
 * 暂停-恢复-超时: PENDING → 人 decide(APPROVED/REJECTED) 或 scheduler 超时扫到 TIMEOUT。
 */
@Data
@TableName("human_task")
public class HumanTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long instanceId;

    private String nodeId;

    private String status;          // PENDING/APPROVED/REJECTED/TIMEOUT

    private LocalDateTime timeoutAt;

    private String assignee;

    private LocalDateTime decidedAt;

    private String decision;        // APPROVED/REJECTED

    private String payload;         // JSON 审批上下文

    private LocalDateTime createdAt;
}
