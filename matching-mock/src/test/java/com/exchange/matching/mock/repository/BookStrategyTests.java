package com.exchange.matching.mock.repository;

import com.exchange.matching.mock.dto.*;
import com.exchange.matching.mock.market.*;
import com.exchange.matching.mock.service.*;
import com.exchange.matching.core.orderbook.MatchingEngine;
import com.exchange.matching.protocol.model.*;
import com.exchange.matching.protocol.command.OrderCommand;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BookStrategyTests {
    BookStrategyRepository repository;
    TradingPairRepository pairs;
    MockOutbox outbox;
    MockInbox inbox;
    TradingService trading;
    BookStrategyService service;
    StrategyHistoryRepository history;
    boolean brokerFailure, delayCancel;
    List<com.exchange.matching.protocol.event.OrderResult> delayed = new ArrayList<>();
    class Source extends BinanceOrderBookClient {
        String version = "100";
        boolean fail;
        int reads;
        List<List<String>> bids=List.of(List.of("100.00","1.23456"));
        List<List<String>> asks=List.of(List.of("101.00","2.34567"));
        @Override public Snapshot read(String symbol, int depth) {
            reads++;
            if (fail) throw new IllegalStateException("source offline");
            return new Snapshot(version, bids.stream().limit(depth).toList(), asks.stream().limit(depth).toList());
        }
    }
    Source source;
    @BeforeEach void setup() throws Exception {
        var database = new MapperTestDatabase(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        pairs = database.pairs(); pairs.initialize(); repository = database.strategies();
        inbox = new MockInbox(database.store().results());
        var engine = new MatchingEngine();
        outbox = new MockOutbox(database.store().commands(), command -> {
            if (brokerFailure) throw new IllegalStateException("broker offline");
            try { var result=engine.process(command).result(); if(delayCancel && command.action()==OrderCommand.Action.CANCEL) delayed.add(result); else inbox.store(result); }
            catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
        });
        trading = new TradingService(outbox, inbox, new HashMap<>(), new MarketTradeHistory());
        history = database.strategyHistory(); source = new Source(); service = new BookStrategyService(repository,pairs,trading,outbox,source,history);
    }
    String add() {
        service.add(new BookStrategyRequest("BTC_USDT",5));
        return service.list().getFirst().id;
    }
    String run() { String id=add(); service.start(id); service.tick(); return id; }
    List<OrderView> open() { return trading.state().orders().stream().filter(OrderView::canCancel).toList(); }
    long cancels() { return outbox.entries().stream().filter(e->e.command().action()==OrderCommand.Action.CANCEL).count(); }
    OrderView buy() { return open().stream().filter(o->o.side().equals("BUY")).findFirst().orElseThrow(); }
    OrderView sell() { return open().stream().filter(o->o.side().equals("SELL")).findFirst().orElseThrow(); }
    @Test void unchangedLevelsPreserveBothSidesAndIdsEvenWhenSourceSequenceChanges() {
        String id=run(); assertEquals(2,open().size()); var before=open();
        source.version="101";service.tick();service.tick();
        assertEquals(before,open());assertEquals(0,cancels());assertEquals(2,outbox.entries().size());
        assertEquals("WATCHING",repository.require(id).phase);
        assertEquals(2,service.snapshots(id).size());
        assertEquals(2,service.snapshotOrders(repository.require(id).snapshotId).size());
    }
    @Test void changesOnlyBuyQuantityAndWaitsForCancelReceiptBeforeReplacement() throws Exception {
        run();String oldBuy=buy().orderId(), unchangedSell=sell().orderId(); delayCancel=true;
        source.bids=List.of(List.of("100.00","3"));service.tick();
        assertEquals(1,cancels());assertEquals(2,trading.state().orders().size());
        service.tick();assertEquals(2,trading.state().orders().size());
        for(var result:delayed) inbox.store(result);delayCancel=false;service.tick();
        assertNotEquals(oldBuy,buy().orderId());assertEquals("3.0000",buy().remainingQuantity());
        assertEquals(unchangedSell,sell().orderId());assertEquals(1,cancels());
    }
    @Test void removedAndNewPricesDoNotTouchUnchangedLevelsOrManualOrders() throws Exception {
        trading.place(new PlaceRequest("manual","BTC_USDT",OrderCommand.Side.BUY,OrderType.LIMIT,TimeInForce.GTC,new BigDecimal("90"),BigDecimal.ONE));
        run();String unchanged=sell().orderId();source.bids=List.of(List.of("99.00","2"));
        service.tick();service.tick();assertEquals(1,cancels());assertEquals(unchanged,sell().orderId());
        assertTrue(open().stream().anyMatch(o->o.commandId().equals("manual")));
        assertTrue(open().stream().anyMatch(o->o.price().equals("99.00") && o.remainingQuantity().equals("2.0000")));
        assertFalse(open().stream().anyMatch(o->o.price().equals("100.00")));
    }
    @Test void fullyFilledBuyIsReplenishedWithoutWaitingForSourceSequenceChange() throws Exception {
        run();String unchanged=sell().orderId(),oldBuy=buy().orderId();
        trading.place(new PlaceRequest("take-buy","BTC_USDT",OrderCommand.Side.SELL,OrderType.LIMIT,TimeInForce.GTC,new BigDecimal("100"),new BigDecimal("1.2345")));
        assertEquals(1,open().size());service.tick();
        assertEquals(2,open().size());assertNotEquals(oldBuy,buy().orderId());assertEquals(unchanged,sell().orderId());assertEquals(0,cancels());
    }
    @Test void partiallyFilledQuantityIsComparedWithActualRemainingQuantity() throws Exception {
        run();String oldBuy=buy().orderId();
        trading.place(new PlaceRequest("partial","BTC_USDT",OrderCommand.Side.SELL,OrderType.LIMIT,TimeInForce.GTC,new BigDecimal("100"),new BigDecimal("0.2345")));
        source.bids=List.of(List.of("100","1"));service.tick();assertEquals(oldBuy,buy().orderId());assertEquals(0,cancels());
        source.bids=List.of(List.of("100","2"));service.tick();service.tick();assertEquals("2.0000",buy().remainingQuantity());assertEquals(1,cancels());
    }
    @Test void unrelatedCancellationDoesNotStarveMissingLevels() {
        run();delayCancel=true;
        source.bids=List.of(List.of("100","2"),List.of("99","1"));
        service.tick();
        assertEquals(1,cancels());
        assertTrue(open().stream().anyMatch(o->o.side().equals("BUY") && o.price().equals("99.00")));
        assertEquals(3,trading.state().orders().size());
    }
    @Test void shiftedSpreadWaitsForOldOppositePriceToBeCancelled() throws Exception {
        run();delayCancel=true;
        source.bids=List.of(List.of("102","1"));source.asks=List.of(List.of("103","2"));service.tick();
        assertFalse(trading.state().orders().stream().anyMatch(o->o.side().equals("BUY") && o.price().equals("102.00")));
        for(var result:delayed)inbox.store(result);delayCancel=false;service.tick();
        assertEquals("102.00",buy().price());assertEquals("103.00",sell().price());
        assertTrue(trading.state().orders().stream().allMatch(o->new BigDecimal(o.filledQuantity()).signum()==0));
    }
    @Test void restartAndSourceFailureKeepExistingOrdersAndResumeDiffing() {
        String id=run();var before=open();repository.recover();service=new BookStrategyService(repository,pairs,trading,outbox,source,history);
        source.fail=true;service.tick();assertEquals(before,open());assertEquals(0,cancels());
        source.fail=false;service.tick();assertEquals(before,open());assertEquals("WATCHING",repository.require(id).phase);
    }
    @Test void brokerFailureRetriesTheOriginalPlanOnBothSidesWithoutNewIds() {
        brokerFailure=true;run();assertEquals(2,outbox.entries().size());var ids=outbox.entries().stream().map(e->e.command().orderId()).toList();
        brokerFailure=false;service.tick();assertEquals(ids,outbox.entries().stream().map(e->e.command().orderId()).toList());assertEquals(2,open().size());assertEquals(0,cancels());
    }
    @Test void editingDepthRetainsUnchangedOrdersAndManualPauseDeletePreservesHistory() {
        String id=run();var before=open();source.bids=List.of(List.of("100","1.23456"),List.of("99","1"));
        service.update(id,new BookStrategyRequest("BTC_USDT",50));service.tick();assertEquals(3,open().size());assertEquals(0,cancels());
        assertTrue(open().containsAll(before));assertEquals(3,service.snapshotOrders(repository.require(id).snapshotId).size());
        service.pause(id,false);service.tick();service.tick();repository.recover();assertEquals("PAUSED",repository.require(id).phase);
        service.tick();assertTrue(open().isEmpty());service.pause(id,true);service.tick();assertTrue(service.list().isEmpty());assertFalse(history.snapshots(id).isEmpty());
    }
    @Test void pendingCancellationSurvivesRestartAndSourceReversion() throws Exception {
        String id=run();String old=buy().orderId();delayCancel=true;source.bids=List.of(List.of("100","2"));service.tick();
        repository.recover();service=new BookStrategyService(repository,pairs,trading,outbox,source,history);
        source.bids=List.of(List.of("100","1.23456"));service.tick();assertEquals(2,trading.state().orders().size());
        for(var result:delayed)inbox.store(result);delayCancel=false;service.tick();assertNotEquals(old,buy().orderId());assertEquals("1.2345",buy().remainingQuantity());
    }
    @Test void everyActivePairIsPolledInEachOneSecondRound() throws Exception {
        String first=add();service.add(new BookStrategyRequest("ETH_USDT",5));String second=service.list().stream().filter(r->r.symbol.equals("ETH_USDT")).findFirst().orElseThrow().id;
        service.start(first);service.start(second);service.tick();assertEquals(2,source.reads);service.tick();assertEquals(4,source.reads);
        assertEquals("${mock.strategy.poll-delay-ms:1000}",BookStrategyService.class.getMethod("tick").getAnnotation(org.springframework.scheduling.annotation.Scheduled.class).fixedRateString());
    }
    @Test void validatesDepthAndRoundsBothSidesWithoutCrossing() {
        add();assertThrows(IllegalArgumentException.class,()->service.add(new BookStrategyRequest("BTC_USDT",5)));
        assertThrows(IllegalArgumentException.class,()->service.add(new BookStrategyRequest("ETH_USDT",51)));
        var snapshot=new BinanceOrderBookClient.Snapshot("1",List.of(List.of("100.009","1.23456"),List.of("100.001","1.00009")),List.of(List.of("100.011","0.00001"),List.of("100.012","2.34567")));
        var plan=BookStrategyService.plan(TradingPair.require("BTC_USDT"),snapshot,5);
        assertEquals(2,plan.size());assertEquals(new BigDecimal("100.00"),plan.getFirst().price());assertEquals(new BigDecimal("2.2345"),plan.getFirst().quantity());assertEquals(new BigDecimal("100.02"),plan.getLast().price());
    }
    @Test void cleanupWaitsForReceiptThenRemovesCancelledReferencesWithoutTouchingLogs() throws Exception {
        String id=run(), oldBuy=buy().commandId(), snapshot=repository.require(id).snapshotId;
        delayCancel=true;source.bids=List.of(List.of("100","2"));service.tick();
        service.cleanupCancelledHistory();
        assertTrue(service.snapshotOrders(snapshot).stream().anyMatch(o->o.commandId.equals(oldBuy)));
        for(var result:delayed) inbox.store(result);delayCancel=false;
        int commandCount=outbox.entries().size(), resultCount=inbox.results().size();
        service.cleanupCancelledHistory();service.cleanupCancelledHistory();
        assertFalse(repository.require(id).plan.contains(oldBuy));
        assertFalse(service.snapshotOrders(snapshot).stream().anyMatch(o->o.commandId.equals(oldBuy)));
        assertEquals(1,service.snapshotOrders(snapshot).size());
        assertEquals(commandCount,outbox.entries().size());assertEquals(resultCount,inbox.results().size());
        repository.recover();service=new BookStrategyService(repository,pairs,trading,outbox,source,history);
        service.tick();assertEquals("2.0000",buy().remainingQuantity());
        assertFalse(service.snapshotOrders(snapshot).stream().anyMatch(o->o.commandId.equals(oldBuy)));
    }
    @Test void cleanupDeletesEmptySnapshotsIncludingDeletedStrategies() {
        String id=run();service.pause(id,true);service.tick();service.tick();
        assertTrue(service.list().isEmpty());assertFalse(service.snapshots(id).isEmpty());
        service.cleanupCancelledHistory();service.cleanupCancelledHistory();
        assertTrue(service.snapshots(id).isEmpty());
        assertFalse(outbox.entries().isEmpty());assertFalse(inbox.results().isEmpty());
    }
    @Test void cleanupRetainsFilledOrderAndTradeEvidence() throws Exception {
        String id=run(), filled=buy().commandId();
        trading.place(new PlaceRequest("fill-before-cleanup","BTC_USDT",OrderCommand.Side.SELL,
                OrderType.LIMIT,TimeInForce.GTC,new BigDecimal("100"),new BigDecimal("1.2345")));
        service.pause(id,false);service.tick();service.tick();service.cleanupCancelledHistory();
        var snapshot=service.snapshots(id).getFirst();var retained=service.snapshotOrders(snapshot.id);
        assertEquals(1,retained.size());assertEquals(filled,retained.getFirst().commandId);
        assertEquals("FILLED",retained.getFirst().status);
        assertTrue(inbox.results().stream().anyMatch(r->!r.trades().isEmpty()));
        assertEquals("PAUSED",repository.require(id).phase);
    }
}
