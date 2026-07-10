package com.orca.agent.tool;

/**
 * Agent 工具接口(策略模式, 每个工具一个实现)。
 *
 * 面考点(OCP): 新增工具只需加一个 @Component 实现, ToolRegistry 自动收集路由, Agent 无感。
 * 生产对应 LLM function calling 的 tool 定义; P4 用 Mock 实现 + 脚本化 ReAct 驱动。
 */
public interface Tool {

    /** 工具名(ReAct Action 调用键, 如 weather) */
    String name();

    /** 工具描述(注入 system prompt 供 LLM/脚本选择) */
    String description();

    /**
     * 执行工具。
     * @param args JSON 参数串(如 {"city":"北京"}), 各工具自解析
     * @return observation 文本(JSON 或自然语言, 喂回 LLM)
     */
    String execute(String args) throws Exception;
}
