package com.exchange.matching.mock.config;

import com.exchange.matching.mock.repository.MysqlMockStore;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import com.exchange.matching.mock.mapper.CommandLogMapper;
import com.exchange.matching.mock.mapper.ResultLogMapper;
import org.mybatis.spring.annotation.MapperScan;

@Configuration
@Profile("database")
@MapperScan("com.exchange.matching.mock.mapper")
public class MockDatabaseConfiguration {
    @Bean
    @DependsOnDatabaseInitialization
    MysqlMockStore mysqlMockStore(CommandLogMapper commands, ResultLogMapper results) { return new MysqlMockStore(commands, results); }
}
