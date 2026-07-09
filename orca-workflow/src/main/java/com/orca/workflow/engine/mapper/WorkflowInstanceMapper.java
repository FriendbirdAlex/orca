package com.orca.workflow.engine.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.orca.workflow.engine.entity.WorkflowInstance;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface WorkflowInstanceMapper extends BaseMapper<WorkflowInstance> {

    @Select("SELECT * FROM workflow_instance WHERE biz_id = #{bizId}")
    WorkflowInstance selectByBizId(@Param("bizId") String bizId);

    /** 调度器核心查询: RUNNING 且 next_schedule_at 为空或已到点, LIMIT 防一次拉太多 */
    @Select("SELECT * FROM workflow_instance WHERE status = 'RUNNING' " +
            "AND (next_schedule_at IS NULL OR next_schedule_at <= #{now}) LIMIT #{limit}")
    List<WorkflowInstance> selectScheduledReady(@Param("now") LocalDateTime now, @Param("limit") int limit);

    /** 调度失败退避: 设 next_schedule_at = now + backoff, 防坏实例空转抢占锁 */
    @Update("UPDATE workflow_instance SET next_schedule_at = #{next} WHERE id = #{id}")
    int updateNextScheduleAt(@Param("id") Long id, @Param("next") LocalDateTime next);
}
