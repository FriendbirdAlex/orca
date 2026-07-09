package com.orca.workflow.dsl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orca.common.exception.BizException;
import com.orca.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * DSL 解析器: Jackson 解析 JSON → WorkflowDsl, 再校验。
 * 解析结果可缓存(DagExecutor.parseAndCache), 避免每次推进重复解析。
 */
@Component
@RequiredArgsConstructor
public class DslParser {

    private final ObjectMapper objectMapper;
    private final DslValidator validator;

    /** 解析 DSL 原文 → WorkflowDsl, 校验失败抛 WF_INVALID_DSL */
    public WorkflowDsl parse(String dslJson) {
        if (dslJson == null || dslJson.isBlank()) {
            throw new BizException(ErrorCode.WF_INVALID_DSL, "DSL 为空");
        }
        WorkflowDsl dsl;
        try {
            dsl = objectMapper.readValue(dslJson, WorkflowDsl.class);
        } catch (Exception e) {
            throw new BizException(ErrorCode.WF_INVALID_DSL, "DSL JSON 解析失败: " + e.getMessage());
        }
        validator.validate(dsl);
        return dsl;
    }
}
