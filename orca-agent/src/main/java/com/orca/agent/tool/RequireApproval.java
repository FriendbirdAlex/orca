package com.orca.agent.tool;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注工具为高风险(需人工确认)。
 * 独立 Agent 接口: 拒绝执行返回跳过 observation; 工作流: 用 HUMAN→AGENT DAG 组合暂停。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireApproval {
    boolean value() default true;
}
