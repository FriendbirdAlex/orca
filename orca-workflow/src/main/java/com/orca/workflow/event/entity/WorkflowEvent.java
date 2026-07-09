package com.orca.workflow.event.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工作流事件(append-only 事件溯源)。
 * seq 实例内单调递增(应用层 MAX+1 + uk_inst_seq 兜底), 可重放重建任意时刻状态。
 */
@Data
@TableName("workflow_event")
public class WorkflowEvent {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long instanceId;

    private Integer seq;

    private String eventType;

    private String payload;         // JSON

    private LocalDateTime createdAt;
}
