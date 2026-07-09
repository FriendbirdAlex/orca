package com.orca.workflow.dsl;

import lombok.Data;

import java.util.List;

/**
 * 工作流 DSL(JSON 解析后结构): nodes + edges 构成 DAG。
 */
@Data
public class WorkflowDsl {
    private List<NodeDef> nodes;
    private List<EdgeDef> edges;
}
