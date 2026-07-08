package com.orca.gateway.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * PgVector 第二数据源配置(语义缓存)。
 *
 * 面考点(为何不注册为 DataSource bean):
 *  Spring Boot 的 DataSourceAutoConfiguration 是 @ConditionalOnMissingBean(DataSource),
 *  一旦注册第二个 DataSource bean, MySQL 的自动配置会退避 → 必须显式声明所有数据源。
 *  这里只暴露 JdbcTemplate bean, HikariDataSource 内部持有(不作为 DataSource bean 注册),
 *  MySQL 主数据源自动配置不受影响。
 *
 * 向量扩展由 PgVector 容器 init SQL 保证(CREATE EXTENSION), Java 侧不再 ensureExtension
 * (避免与 JdbcTemplate bean 形成循环依赖)。
 * 向量参数用字符串 cast(?::vector), 无需 pgvector Java 类型处理器, 仅依赖 PGJDBC。
 */
@Slf4j
@Configuration
public class PgVectorConfig {

    @Value("${orca.pgvector.url}")
    private String url;

    @Value("${orca.pgvector.username}")
    private String username;

    @Value("${orca.pgvector.password}")
    private String password;

    @Value("${orca.pgvector.pool-size:8}")
    private int poolSize;

    private HikariDataSource pgvectorDataSource;

    @Bean(name = "pgvectorJdbcTemplate")
    public JdbcTemplate pgvectorJdbcTemplate() {
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(url);
        hc.setUsername(username);
        hc.setPassword(password);
        hc.setMaximumPoolSize(poolSize);
        hc.setMinimumIdle(0);              // 不预建连接, PgVector 未启动时不阻塞 bean 创建
        hc.setConnectionTimeout(5000);
        hc.setInitializationFailTimeout(-1); // 不在初始化时强制连接检查, 允许 PgVector 后启动
        hc.setPoolName("orca-pgvector");
        this.pgvectorDataSource = new HikariDataSource(hc);
        log.info("[pgvector] 第二数据源已创建 url={}", url);
        return new JdbcTemplate(pgvectorDataSource);
    }

    @PreDestroy
    public void close() {
        if (pgvectorDataSource != null && !pgvectorDataSource.isClosed()) {
            pgvectorDataSource.close();
            log.info("[pgvector] 数据源已关闭");
        }
    }
}

