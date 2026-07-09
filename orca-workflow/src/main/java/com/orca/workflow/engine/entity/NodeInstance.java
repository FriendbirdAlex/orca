package com.orca.workflow.engine.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 节点实例(每节点每实例一行, uk_inst_node 幂等)。
 */
@Data
@TableName("node_instance")
public class NodeInstance {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long instanceId;

    private String nodeId;

    private String status;          // PENDING/RUNNING/SUCCEEDED/FAILED/SKIPPED

    private String input;           // JSON

    private String output;          // JSON

    private Integer retryCount;

    private LocalDateTime startedAt;

    private LocalDateTime endedAt;

    private String error;
}
