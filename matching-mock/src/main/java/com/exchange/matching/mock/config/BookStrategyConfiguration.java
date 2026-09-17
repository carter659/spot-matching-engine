package com.exchange.matching.mock.config;

import com.exchange.matching.mock.market.BinanceOrderBookClient;
import com.exchange.matching.mock.mapper.BookStrategyMapper;
import com.exchange.matching.mock.repository.*;
import com.exchange.matching.mock.service.*;
import org.springframework.context.annotation.*;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;

@Configuration
@org.springframework.scheduling.annotation.EnableScheduling
@Profile("database & messaging")
public class BookStrategyConfiguration {
    @Bean @DependsOnDatabaseInitialization
    BookStrategyRepository bookStrategyRepository(BookStrategyMapper mapper) {
        var repository = new BookStrategyRepository(mapper); repository.recover(); return repository;
    }
    @Bean BinanceOrderBookClient binanceOrderBookClient() { return new BinanceOrderBookClient(); }
    @Bean @DependsOnDatabaseInitialization
    StrategyHistoryRepository strategyHistoryRepository(com.exchange.matching.mock.mapper.StrategySnapshotMapper snapshots,
            com.exchange.matching.mock.mapper.StrategyOrderMapper orders) { return new StrategyHistoryRepository(snapshots, orders); }
    @Bean BookStrategyService bookStrategyService(BookStrategyRepository repository, TradingPairRepository pairs,
            TradingService trading, MockOutbox outbox, BinanceOrderBookClient source, StrategyHistoryRepository history) {
        return new BookStrategyService(repository, pairs, trading, outbox, source, history);
    }
}
