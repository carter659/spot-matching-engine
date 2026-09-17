package com.exchange.matching.mock.config;
import com.exchange.matching.mock.market.BinanceTradeStream;
import com.exchange.matching.mock.repository.*;
import com.exchange.matching.mock.mapper.*;
import com.exchange.matching.mock.service.*;
import org.springframework.context.annotation.*;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
@Configuration @Profile("database & messaging") @org.springframework.scheduling.annotation.EnableScheduling
public class TradeStrategyConfiguration {
 @Bean org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler tradeStrategyScheduler(){
  var scheduler=new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
  scheduler.setPoolSize(1);scheduler.setThreadNamePrefix("trade-strategy-");return scheduler;
 }
 @Bean org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler taskScheduler(){
  var scheduler=new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
  scheduler.setPoolSize(2);scheduler.setThreadNamePrefix("mock-background-");return scheduler;
 }
 @Bean(destroyMethod="close") BinanceTradeStream binanceTradeStream(){return new BinanceTradeStream();}
 @Bean @DependsOnDatabaseInitialization TradeStrategyRepository tradeStrategyRepository(TradeStrategyMapper strategies,TradeReplayMapper records,javax.sql.DataSource source){TradeStrategySchema.migrate(source);return new TradeStrategyRepository(strategies,records);}
 @Bean TradeStrategyService tradeStrategyService(TradeStrategyRepository repository,TradingPairRepository pairs,TradingService trading,BinanceTradeStream source){return new TradeStrategyService(repository,pairs,trading,source);}
}

