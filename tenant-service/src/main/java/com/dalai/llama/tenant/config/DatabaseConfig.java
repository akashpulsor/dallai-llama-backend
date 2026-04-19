package com.dalai.llama.tenant.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Database Configuration for Multiple Datasources
 *
 * - Primary: tenant-service database (dalaillama)
 * - Kamailio: Kamailio SIP proxy database (kamailio)
 */
@Configuration
public class DatabaseConfig {

    // ================================================================
    // PRIMARY DATASOURCE (tenant-service)
    // ================================================================

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties primaryDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @Primary
    public DataSource primaryDataSource() {
        return primaryDataSourceProperties()
                .initializeDataSourceBuilder()
                .build();
    }


}