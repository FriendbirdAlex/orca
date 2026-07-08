package com.orca.gateway.quota.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;

@Data
@TableName("tenant_quota")
public class TenantQuota {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;

    private LocalDate quotaDate;

    private Long dailyLimit;

    private Long consumedTokens;
}
