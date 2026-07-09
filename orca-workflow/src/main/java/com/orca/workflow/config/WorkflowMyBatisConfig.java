package com.orca.workflow.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * 工作流 MyBatis 配置: 扫描 com.orca.workflow 下的 @Mapper。
 *
 * 注意(踩坑): 必须带 annotationClass = Mapper.class, 否则把 NodeExecutor/NodeDef 等业务接口
 *  当 Mapper 注册, 注入 List<NodeExecutor> 时混入 MyBatis 代理报 "Invalid bound statement"。
 * 分页插件不在此重复建, 复用 gateway 的 MybatisPlusInterceptor bean。
 */
@Configuration
@MapperScan(basePackages = "com.orca.workflow", annotationClass = org.apache.ibatis.annotations.Mapper.class)
public class WorkflowMyBatisConfig {
}
