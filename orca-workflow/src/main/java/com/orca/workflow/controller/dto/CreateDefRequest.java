package com.orca.workflow.controller.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateDefRequest {
    @NotBlank(message = "workflowCode 不能为空")
    private String workflowCode;

    private Integer version = 1;

    @NotBlank(message = "name 不能为空")
    private String name;

    @NotBlank(message = "dsl 不能为空")
    private String dsl;
}
