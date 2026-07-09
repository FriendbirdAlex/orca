package com.orca.workflow.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class DecideRequest {
    /** APPROVED / REJECTED */
    @NotBlank
    @Pattern(regexp = "APPROVED|REJECTED", message = "decision 只能是 APPROVED 或 REJECTED")
    private String decision;

    private String comment;
}
