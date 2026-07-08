package com.orca.gateway.service.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("tenant")
public class Tenant {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String tenantCode;

    private String tenantName;

    private Integer status;     // 1启用 0禁用

    private Integer rpmLimit;

    private Integer tpmLimit;

    private Long dailyQuota;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
