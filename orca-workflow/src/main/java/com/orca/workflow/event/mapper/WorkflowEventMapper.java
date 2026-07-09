package com.orca.workflow.event.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.orca.workflow.event.entity.WorkflowEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface WorkflowEventMapper extends BaseMapper<WorkflowEvent> {

    /** 事件序号: 应用层取 MAX(seq)+1, uk_inst_seq 兜底防并发 */
    @Select("SELECT COALESCE(MAX(seq), 0) FROM workflow_event WHERE instance_id = #{instanceId}")
    Integer selectMaxSeq(@Param("instanceId") Long instanceId);

    /** 重放: 按实例查全部事件(ORDER BY seq), 重建最终状态 */
    @Select("SELECT * FROM workflow_event WHERE instance_id = #{instanceId} ORDER BY seq")
    List<WorkflowEvent> selectByInstance(@Param("instanceId") Long instanceId);
}
