package com.orca.gateway.service.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.orca.gateway.service.entity.ApiKey;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ApiKeyMapper extends BaseMapper<ApiKey> {
}
