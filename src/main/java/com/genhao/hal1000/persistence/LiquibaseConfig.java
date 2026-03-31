package com.genhao.hal1000.persistence;

import liquibase.integration.spring.SpringLiquibase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Ensure Liquibase runs on startup for SQLite.
 * (We observed Liquibase auto-configuration not triggering in some environments.)
 */
@Configuration
public class LiquibaseConfig {

    @Bean
    SpringLiquibase liquibase(DataSource dataSource) {
        var lb = new SpringLiquibase();
        lb.setDataSource(dataSource);
        lb.setChangeLog("classpath:db/changelog/db.changelog-master.yaml");
        lb.setShouldRun(true);
        return lb;
    }
}

