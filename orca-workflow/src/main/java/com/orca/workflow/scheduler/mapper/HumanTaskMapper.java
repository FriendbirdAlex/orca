package com.orca.workflow.scheduler.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.orca.workflow.scheduler.entity.HumanTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface HumanTaskMapper extends BaseMapper<HumanTask> {

    /** 超时扫描: PENDING 且 timeout_at <= now */
    @Select("SELECT * FROM human_task WHERE status = 'PENDING' AND timeout_at <= #{now}")
    List<HumanTask> selectExpired(@Param("now") java.time.LocalDateTime now);

    @Select("SELECT * FROM human_task WHERE instance_id = #{instanceId} AND status = 'PENDING' ORDER BY id DESC LIMIT 1")
    HumanTask selectPendingByInstance(@Param("instanceId") Long instanceId);
}
