package com.orca.gateway.service.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.orca.gateway.service.entity.Tenant;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TenantMapper extends BaseMapper<Tenant> {
}
