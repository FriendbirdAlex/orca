package com.orca.gateway.billing;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("call_log")
public class CallLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;

    private Long apiKeyId;

    private String requestId;

    private String provider;

    private String model;

    private Integer stream;   // 0/1

    private Integer promptTokens;

    private Integer completionTokens;

    private Integer totalTokens;

    private Integer latencyMs;

    private String status;    // success / fail

    private Integer errorCode;

    private String traceId;

    private LocalDateTime createdAt;
}
