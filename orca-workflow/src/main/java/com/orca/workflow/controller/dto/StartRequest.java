package com.orca.workflow.controller.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.Map;

@Data
public class StartRequest {
    @NotBlank(message = "workflowCode 不能为空")
    private String workflowCode;

    private Integer version = 1;

    @NotBlank(message = "bizId 不能为空")
    private String bizId;

    private Map<String, Object> input;
}
