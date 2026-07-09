package com.orca.workflow.task;

import com.orca.workflow.dsl.NodeDef;
import com.orca.workflow.dsl.NodeType;
import com.orca.workflow.engine.InstanceContext;
import com.orca.workflow.engine.entity.NodeInstance;

import java.util.Map;

/**
 * 节点执行器接口(策略模式, 每种节点类型一个实现)。
 *
 * 面考点(OCP): 新增节点类型只需加一个实现 + @Component, DagExecutor 按 supportType 路由, 无需改引擎。
 */
public interface NodeExecutor {

    /** 支持的节点类型 */
    NodeType supportType();

    /** 正向执行, 返回节点 output(供下游节点引用) */
    Map<String, Object> execute(NodeDef node, InstanceContext ctx) throws Exception;

    /** 补偿(Saga 失败逆序调用), 默认空实现(部分节点无副作用无需补偿) */
    default void compensate(NodeDef node, InstanceContext ctx, NodeInstance ni) {
    }
}
