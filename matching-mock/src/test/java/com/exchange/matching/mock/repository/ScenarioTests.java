package com.exchange.matching.mock.repository;
import com.exchange.matching.mock.service.*;
import com.exchange.matching.mock.market.*;
import com.exchange.matching.core.orderbook.MatchingEngine;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ScenarioTests {
 @Test void allScenariosAssertRealEngineResultsAndCleanOwnOrders()throws Exception{
  var db=new MapperTestDatabase(new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""));
  var pairs=db.pairs();pairs.initialize();var inbox=new MockInbox(db.store().results());var engine=new MatchingEngine();
  var outbox=new MockOutbox(db.store().commands(),c->{try{inbox.store(engine.process(c).result());}catch(Exception e){throw new RuntimeException(e);}});
  var trading=new TradingService(outbox,inbox,new HashMap<>(),new MarketTradeHistory());var service=new ScenarioService(trading,inbox,pairs,db.scenarios());
  assertThrows(IllegalArgumentException.class,()->service.start("BTC_USDT","PRICE",false));
  for(String scenario:List.of("PRICE","FIFO","PARTIAL","CANCEL","IOC","FOK","DUPLICATE")){
   var run=service.start("BTC_USDT",scenario,true);long deadline=System.currentTimeMillis()+5000;
   while(db.scenarios().selectById(run.id).status.equals("RUNNING")&&System.currentTimeMillis()<deadline)Thread.sleep(20);
   var saved=db.scenarios().selectById(run.id);assertEquals("PASSED",saved.status,saved.report);
   assertTrue(trading.state("BTC_USDT").orders().stream().noneMatch(o->o.canCancel()));
  }
 }
}
