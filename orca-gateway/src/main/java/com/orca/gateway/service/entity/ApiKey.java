package com.orca.gateway.service.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("api_key")
public class ApiKey {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;

    private String apiKey;

    private String keyPrefix;

    private Integer status;     // 1启用 0禁用

    private LocalDateTime lastUsedAt;

    private LocalDateTime createdAt;
}
