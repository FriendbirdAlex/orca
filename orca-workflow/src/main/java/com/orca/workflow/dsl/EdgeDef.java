package com.orca.workflow.dsl;

import lombok.Data;

/**
 * DAG 边定义: source → target。
 */
@Data
public class EdgeDef {
    private String source;
    private String target;
}
