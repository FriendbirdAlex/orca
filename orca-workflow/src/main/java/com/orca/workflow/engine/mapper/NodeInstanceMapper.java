package com.orca.workflow.engine.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.orca.workflow.engine.entity.NodeInstance;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface NodeInstanceMapper extends BaseMapper<NodeInstance> {

    @Select("SELECT * FROM node_instance WHERE instance_id = #{instanceId} AND node_id = #{nodeId}")
    NodeInstance selectByInstNode(@Param("instanceId") Long instanceId, @Param("nodeId") String nodeId);

    /** Saga 补偿用: 取所有 SUCCEEDED 节点(逆序补偿) */
    @Select("SELECT * FROM node_instance WHERE instance_id = #{instanceId} AND status = 'SUCCEEDED' ORDER BY ended_at DESC")
    List<NodeInstance> selectSucceededByInstance(@Param("instanceId") Long instanceId);

    @Select("SELECT * FROM node_instance WHERE instance_id = #{instanceId}")
    List<NodeInstance> selectByInstance(@Param("instanceId") Long instanceId);
}
