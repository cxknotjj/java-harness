package com.dark.javaHarness.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 主业务库（MySQL）显式声明：spring.datasource.* 照旧是配置唯一来源。
 *
 * <p>为什么需要：引入知识库 vectorDataSource（DataSource 子类 bean）后，Spring Boot
 * DataSourceAutoConfiguration 的 {@code @ConditionalOnMissingBean(DataSource)} 条件失效——
 * 自动配置的主库不再创建，容器唯一 DataSource 候选变成 pgvector，Flyway/MyBatis 全部错拿。
 * 显式声明主库并标 {@code @Primary} 后：主库恢复存在且成为 byType 注入默认候选
 * （Flyway 迁移、MyBatis-Plus、MysqlSaver 检查点均按 @Primary 取主库），
 * 知识库数据源只供 KnowledgeConfig 内部按名引用（@Lazy 参数注入）。
 */
@Configuration
public class PrimaryDataSourceConfig {

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties dataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource dataSource(DataSourceProperties dataSourceProperties) {
        return dataSourceProperties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }
}
