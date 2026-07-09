package com.orca.workflow.dsl.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.orca.workflow.dsl.entity.WorkflowDefinition;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface WorkflowDefinitionMapper extends BaseMapper<WorkflowDefinition> {

    @Select("SELECT * FROM workflow_definition WHERE workflow_code = #{code} AND version = #{version}")
    WorkflowDefinition selectByCodeVersion(@Param("code") String workflowCode, @Param("version") Integer version);
}
