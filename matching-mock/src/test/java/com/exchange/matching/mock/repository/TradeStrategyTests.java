package com.exchange.matching.mock.repository;
import com.exchange.matching.mock.dto.*;
import com.exchange.matching.mock.market.*;
import com.exchange.matching.mock.service.*;
import com.exchange.matching.core.orderbook.MatchingEngine;
import com.exchange.matching.protocol.command.OrderCommand;
import com.exchange.matching.protocol.model.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class TradeStrategyTests {
 TradeStrategyRepository repo;TradingPairRepository pairs;TradingService trading;MockOutbox outbox;MockInbox inbox;TradeStrategyService service;boolean fail;Map<String,com.exchange.matching.protocol.event.MarketUpdate> market=new HashMap<>();
 class Time extends Clock { long millis=1800000000000L;public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return Instant.ofEpochMilli(millis);}public long millis(){return millis;} }
 Time clock=new Time();
 class Feed extends BinanceTradeStream {List<Trade> batch=new ArrayList<>();boolean stopped;public List<Trade> poll(String symbol){var result=List.copyOf(batch);batch.clear();return result;}public String status(String symbol){return "test";}public void stop(String symbol){stopped=true;batch.clear();} }
 Feed source=new Feed();
 @BeforeEach void setup()throws Exception{
  var database=new MapperTestDatabase(new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""));
  repo=database.tradeStrategies();pairs=database.pairs();pairs.initialize();inbox=new MockInbox(database.store().results());var engine=new MatchingEngine();
  outbox=new MockOutbox(database.store().commands(),c->{if(fail)throw new IllegalStateException("offline");try{var processed=engine.process(c);inbox.store(processed.result());market.put(c.symbol(),processed.market());}catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}});
  trading=new TradingService(outbox,inbox,market,new MarketTradeHistory());service=new TradeStrategyService(repo,pairs,trading,source,clock);
 }
 TradeStrategyRequest config(int rate){return new TradeStrategyRequest("BTC_USDT",new BigDecimal("2"),rate,new BigDecimal("3"),5);}
 String start(int rate){service.add(config(rate));String id=repo.list().getFirst().id;service.running(id,true);return id;}
 BinanceTradeStream.Trade trade(long id,boolean maker){return new BinanceTradeStream.Trade(id,clock.millis,new BigDecimal("100.009"),new BigDecimal("10"),maker);}
 @Test void buyUsesIocCapAndRealResultRatherThanPublishConfirmation()throws Exception{
  trading.place(new PlaceRequest("maker","BTC_USDT",OrderCommand.Side.SELL,OrderType.LIMIT,TimeInForce.GTC,new BigDecimal("99"),BigDecimal.ONE));
  String id=start(5);service.accept(id,trade(10,false));var record=repo.records(id,1).getFirst();
  assertEquals("BUY",record.side);assertEquals("100.00",record.price);assertEquals("3.0000",record.quantity);assertEquals("BROKER_CONFIRMED",record.status);assertEquals(0L,repo.stats(id).get("matched"));
  service.tick();record=repo.records(id,1).getFirst();assertEquals("1.0000",record.filledQuantity);assertEquals("EXPIRED",record.status);assertEquals(1L,repo.stats(id).get("matched"));
  assertTrue(outbox.entries().stream().filter(e->e.command().commandId().startsWith("replay-")).allMatch(e->e.command().timeInForce()==TimeInForce.IOC));
 }
 @Test void makerBuyerMeansActiveSellAndPriceRoundsUp()throws Exception{String id=start(5);service.accept(id,trade(11,true));var r=repo.records(id,1).getFirst();assertEquals("SELL",r.side);assertEquals("100.01",r.price);}
 @Test void dedupPersistsAcrossRestartDeleteAndRecreate()throws Exception{
  String id=start(5);service.accept(id,trade(12,false));String order=repo.records(id,1).getFirst().orderId;
  service=new TradeStrategyService(repo,pairs,trading,source,clock);service.accept(id,trade(12,false));assertEquals(1,repo.count(id));
  service.delete(id);assertTrue(repo.list().isEmpty());service.add(config(5));service.running(id,true);service.accept(id,trade(12,false));assertEquals(1,repo.count(id));assertEquals(order,repo.records(id,1).getFirst().orderId);
 }
 @Test void limitsStaleFutureAndPausedTradesWithoutBackfill()throws Exception{
  String id=start(1);service.accept(id,trade(1,false));service.accept(id,trade(2,false));assertEquals(1,repo.count(id));assertEquals(1L,repo.require(id).skipped);
  clock.millis+=1000;service.accept(id,trade(3,false));assertEquals(2,repo.count(id));
  service.accept(id,new BinanceTradeStream.Trade(4,clock.millis-10000,BigDecimal.ONE,BigDecimal.ONE,false));assertEquals(2L,repo.require(id).skipped);
  service.running(id,false);service.accept(id,trade(5,false));assertEquals(2,repo.count(id));assertTrue(source.stopped);
 }
 @Test void failedPublicationRetriesOriginalSnowflakeIdEvenAfterPause()throws Exception{
  String id=start(5);fail=true;assertThrows(Exception.class,()->service.accept(id,trade(30,false)));long allocated=outbox.entries().getFirst().command().orderId();
  service.running(id,false);fail=false;service=new TradeStrategyService(repo,pairs,trading,source,clock);service.tick();service.tick();
  assertEquals(1,outbox.entries().size());assertEquals(Long.toString(allocated),repo.records(id,1).getFirst().orderId);assertEquals("EXPIRED",repo.records(id,1).getFirst().status);
 }
 @Test void rejectsBadConfigurationAndSupportsEditing(){String id=start(5);assertThrows(IllegalArgumentException.class,()->service.add(config(5)));assertThrows(IllegalArgumentException.class,()->service.update(id,config(101)));service.update(id,config(2));assertEquals(2,repo.require(id).maxPerSecond);assertTrue(repo.require(id).running);}
 @Test void publicStreamParserValidatesSymbolAndDirection(){String raw="{\"e\":\"aggTrade\",\"s\":\"BTCUSDT\",\"a\":123,\"p\":\"100.01\",\"q\":\"2\",\"T\":1800000000000,\"m\":true}";assertTrue(BinanceTradeStream.parse(raw,"BTC_USDT").buyerMaker());assertThrows(IllegalArgumentException.class,()->BinanceTradeStream.parse(raw,"ETH_USDT"));}
 @Test void sweepConsumesManySmallOrdersInOneCommandAndKeepsRunning()throws Exception{
  for(int i=0;i<20;i++)trading.place(new PlaceRequest("small-"+i,"BTC_USDT",OrderCommand.Side.SELL,OrderType.LIMIT,TimeInForce.GTC,new BigDecimal("100"),new BigDecimal("0.1")));
  trading.place(new PlaceRequest("better","BTC_USDT",OrderCommand.Side.SELL,OrderType.LIMIT,TimeInForce.GTC,new BigDecimal("99"),BigDecimal.ONE));
  service.add(new TradeStrategyRequest("BTC_USDT",BigDecimal.ONE,5,new BigDecimal("5"),5,"SWEEP",null,null));String id=repo.list().getFirst().id;service.running(id,true);
  service.accept(id,new BinanceTradeStream.Trade(100,clock.millis,new BigDecimal("98"),BigDecimal.ONE,false));assertEquals(0,repo.count(id));
  service.accept(id,new BinanceTradeStream.Trade(101,clock.millis,new BigDecimal("100"),new BigDecimal("0.0001"),false));
  assertEquals(1,repo.count(id));assertEquals(0,new BigDecimal(repo.records(id,1).getFirst().quantity).compareTo(new BigDecimal("3")));assertTrue(repo.require(id).running);
  assertThrows(IllegalArgumentException.class,()->service.running(id,true));service.tick();assertEquals("FILLED",repo.records(id,1).getFirst().status);assertTrue(market.get("BTC_USDT").orderBook().asks().isEmpty());
 }
 @Test void sweepSellUsesBidQuantityAndOverLimitDoesNotSplit()throws Exception{
  trading.place(new PlaceRequest("bid","BTC_USDT",OrderCommand.Side.BUY,OrderType.LIMIT,TimeInForce.GTC,new BigDecimal("100"),new BigDecimal("2")));
  service.add(new TradeStrategyRequest("BTC_USDT",BigDecimal.ONE,5,BigDecimal.ONE,5,"SWEEP",null,null));String id=repo.list().getFirst().id;service.running(id,true);
  service.accept(id,new BinanceTradeStream.Trade(200,clock.millis,new BigDecimal("100"),BigDecimal.ONE,true));assertEquals(0,repo.count(id));assertTrue(repo.require(id).running);
  service.update(id,new TradeStrategyRequest("BTC_USDT",BigDecimal.ONE,5,new BigDecimal("3"),5,"SWEEP",null,null));
  service.accept(id,new BinanceTradeStream.Trade(201,clock.millis,new BigDecimal("99"),BigDecimal.ONE,true));service.tick();assertEquals("FILLED",repo.records(id,1).getFirst().status);assertTrue(market.get("BTC_USDT").orderBook().bids().isEmpty());
 } @Test void bothModesReceiveSameTradeAndPauseIndependently()throws Exception{
  trading.place(new PlaceRequest("liquidity","BTC_USDT",OrderCommand.Side.SELL,OrderType.LIMIT,TimeInForce.GTC,new BigDecimal("100"),new BigDecimal("10")));
  String follow=start(5);
  service.add(new TradeStrategyRequest("BTC_USDT",BigDecimal.ONE,5,new BigDecimal("20"),5,"SWEEP",null,null));
  String sweep=repo.bySymbol("BTC_USDT","SWEEP").id;service.running(sweep,true);
  assertThrows(IllegalArgumentException.class,()->service.add(config(5)));
  source.batch.add(trade(800,false));service.tick();
  assertEquals(1,repo.count(follow));assertEquals(1,repo.count(sweep));
  assertNotEquals(repo.records(follow,1).getFirst().commandId,repo.records(sweep,1).getFirst().commandId);
  assertTrue(repo.require(follow).running);assertTrue(repo.require(sweep).running);assertFalse(source.stopped);
  service.delete(sweep);assertFalse(source.stopped);
  service.running(follow,false);assertTrue(source.stopped);
 } @Test void sweepWaitsForReceiptAndNewBookThenRepeats()throws Exception{
  trading.place(new PlaceRequest("cycle1","BTC_USDT",OrderCommand.Side.SELL,OrderType.LIMIT,TimeInForce.GTC,new BigDecimal("100"),BigDecimal.ONE));
  service.add(new TradeStrategyRequest("BTC_USDT",BigDecimal.ONE,5,new BigDecimal("20"),5,"SWEEP",null,null));String id=repo.bySymbol("BTC_USDT","SWEEP").id;service.running(id,true);
  var oldBook=market.get("BTC_USDT");service.accept(id,trade(901,false));
  service.accept(id,trade(902,false));assertEquals(1,repo.count(id));service.tick();
  market.put("BTC_USDT",oldBook);service.accept(id,trade(903,false));assertEquals(1,repo.count(id));
  trading.place(new PlaceRequest("cycle2","BTC_USDT",OrderCommand.Side.SELL,OrderType.LIMIT,TimeInForce.GTC,new BigDecimal("100"),BigDecimal.ONE));
  service.accept(id,trade(904,false));assertEquals(2,repo.count(id));assertTrue(repo.require(id).running);
  service.running(id,false);service.accept(id,trade(905,false));assertEquals(2,repo.count(id));
 } @Test void schedulerUsesNewestTradesInsteadOfProcessingBacklog()throws Exception{
  String id=start(1);for(int i=1;i<=1000;i++)source.batch.add(trade(i,false));service.tick();
  assertEquals(1,repo.count(id));assertEquals("1000",repo.records(id,1).getFirst().sourceTradeId);
  assertEquals(1000L,repo.require(id).received);assertEquals(999L,repo.require(id).skipped);
  clock.millis+=1000;source.batch.add(trade(1001,false));service.tick();assertEquals(2,repo.count(id));
 }}






