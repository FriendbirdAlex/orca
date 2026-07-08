package com.orca.gateway.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置: 扫描 gateway 下所有包的 Mapper + 分页插件。
 *
 * MapperScan 用 com.orca.gateway 扫全部子包(部分 Mapper 直接在业务包根下, 如 billing.CallLogMapper),
 * 配合各 Mapper 上的 @Mapper 注解, 确保都被注册。
 *  覆盖: service.mapper(Tenant/ApiKey) / billing(CallLog) / quota.mapper(TenantQuota)
 */
@Configuration
@MapperScan("com.orca.gateway")
public class MyBatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
