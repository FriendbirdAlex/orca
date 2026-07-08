package com.orca.gateway.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置: 扫描 gateway 下所有包的 Mapper + 分页插件。
 *
 * 面试点(踩坑): @MapperScan("com.orca.gateway") 会把包下所有接口都当 Mapper 注册,
 *  包括 LlmProvider/ProviderRouter 等业务接口 → 注入 List<LlmProvider> 时混入 MyBatis 代理,
 *  调用其方法报 "Invalid bound statement"。
 *  解法: annotationClass = Mapper.class, 只注册显式标注 @Mapper 的接口。
 */
@Configuration
@MapperScan(basePackages = "com.orca.gateway", annotationClass = Mapper.class)
public class MyBatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}

