package com.orca.gateway.billing;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("billing_record")
public class BillingRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;

    private LocalDate periodDate;

    private Long totalTokens;

    private Integer totalCalls;

    private Integer successCalls;

    private BigDecimal billingAmount;

    private Long redisConsumed;

    private Long quotaConsumed;

    private Integer diffFlag;   // 0/1

    private Integer settled;    // 0/1

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
