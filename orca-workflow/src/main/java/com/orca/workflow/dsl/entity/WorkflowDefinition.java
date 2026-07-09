package com.orca.workflow.dsl.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工作流定义(DSL 持久化, 版本化)。
 * instance 绑定具体 version, 保证可复现。
 */
@Data
@TableName("workflow_definition")
public class WorkflowDefinition {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String workflowCode;

    private Integer version;

    private String name;

    /** DSL 原文 JSON(MyBatis-Plus 存 String, 解析交给 DslParser) */
    private String dsl;

    private Integer status;   // 1启用 0禁用

    private LocalDateTime createdAt;
}
