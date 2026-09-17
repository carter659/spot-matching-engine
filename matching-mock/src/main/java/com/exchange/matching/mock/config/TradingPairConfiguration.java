package com.exchange.matching.mock.config;

import com.exchange.matching.mock.repository.TradingPairRepository;
import org.springframework.context.annotation.*;
import com.exchange.matching.mock.mapper.TradingPairMapper;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;

@Configuration
@Profile("database")
public class TradingPairConfiguration {
    @Bean
    @DependsOnDatabaseInitialization
    TradingPairRepository tradingPairRepository(TradingPairMapper mapper) {
        var repository = new TradingPairRepository(mapper);
        repository.initialize();
        return repository;
    }
}
