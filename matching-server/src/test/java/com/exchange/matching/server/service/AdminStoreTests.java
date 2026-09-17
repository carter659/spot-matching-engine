package com.exchange.matching.server.service;
import com.exchange.matching.server.repository.AdminStore;
import com.exchange.matching.core.orderbook.EngineParameters;
import com.exchange.matching.protocol.command.OrderCommand;
import com.exchange.matching.protocol.event.OrderResult;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AdminStoreTests {
    @TempDir Path directory;
    OrderCommand order(String id,long orderId,String symbol,OrderCommand.Side side) {return new OrderCommand(id,OrderCommand.Action.PLACE,orderId,symbol,side,100,10);}
    @Test void preferencesPersistAcrossDatabaseReopen() throws Exception {
        var preference=new com.exchange.matching.server.dto.DashboardPreferences("light","en");
        try(var store=new AdminStore(directory)) {
            assertEquals("dark",store.dashboardPreferences().theme());
            store.saveDashboardPreferences(preference);
            assertThrows(IllegalArgumentException.class,()->store.saveDashboardPreferences(new com.exchange.matching.server.dto.DashboardPreferences("bad","en")));
            assertEquals(preference,store.dashboardPreferences());
        }
        try(var store=new AdminStore(directory)) {assertEquals(preference,store.dashboardPreferences());assertTrue(store.authenticate("root","root"));}
    }
    @Test void passwordsPersistWithoutResetOnRestart() throws Exception {
        try(var store=new AdminStore(directory)){
            assertTrue(store.authenticate("root","root"));assertFalse(store.authenticate("other","root"));
            assertFalse(store.changePassword("wrong","new-secret"));
            assertTrue(store.changePassword("root","new-secret"));
            assertEquals(2,store.version());
        }
        try(var store=new AdminStore(directory)) {assertFalse(store.authenticate("root","root"));assertTrue(store.authenticate("root","new-secret"));}
    }
    @Test void migratesHistoryAndReplaysH2ConfigurationAtCorrectBoundaries() throws Exception {
        Path journal=directory.resolve("commands");
        var rejected=order("rejected",1,"CUSTOM_USDT",OrderCommand.Side.BUY);
        var maker=order("maker",2,"CUSTOM_USDT",OrderCommand.Side.SELL);
        try(var service=ReliableMatchingService.open(journal)) {
            assertEquals(OrderResult.Status.REJECTED,service.process(rejected).result().status());
            service.configure(new EngineParameters(Set.of("CUSTOM_USDT"),1,100));service.process(maker);
        }
        try(var store=new AdminStore(directory.resolve("db"));var service=ReliableMatchingService.open(journal,store,new EngineMetrics())){
            assertEquals(2,store.readAll().size());
            assertEquals(OrderResult.Status.REJECTED,service.process(rejected).result().status());
            service.configure(new EngineParameters(Set.of("CUSTOM_USDT","BTC_USDT"),5,50));
        }
        try(var store=new AdminStore(directory.resolve("db"));var service=ReliableMatchingService.open(journal,store,new EngineMetrics())){
            assertEquals(3,store.readAll().size());assertEquals(5,service.configuration().parameters().minOrderLots());
            assertEquals(OrderResult.Status.REJECTED,service.process(rejected).result().status());
            assertEquals(OrderResult.Status.FILLED,service.process(order("taker",3,"CUSTOM_USDT",OrderCommand.Side.BUY)).result().status());
            Path snapshot=directory.resolve("backup");service.snapshot(snapshot);
            Path restored=SnapshotRecovery.restore(snapshot,directory.resolve("restored"));
            try(var replay=ReliableMatchingService.open(restored)){assertEquals(service.configuration(),replay.configuration());}
        }
    }
    @Test void deduplicationSurvivesRecoveryButDashboardStartsEmpty() throws Exception {
        var journal=directory.resolve("commands");var metrics=new EngineMetrics();
        try(var store=new AdminStore(directory.resolve("db"));var service=ReliableMatchingService.open(journal,store,metrics)){
            var maker=order("maker",1,"BTC_USDT",OrderCommand.Side.SELL);service.process(maker);service.process(maker);
            var taker=order("taker",2,"BTC_USDT",OrderCommand.Side.BUY);service.process(taker);service.process(taker);
            assertCounter(metrics,"commands",2);assertCounter(metrics,"trades",1);
            assertEquals("100",EngineMetricsTests.pair(metrics,"BTC_USDT").get("latestPriceTicks"));
            assertEquals("10",EngineMetricsTests.pair(metrics,"BTC_USDT").get("latestQuantityLots"));
        }
        var restored=new EngineMetrics();
        try(var store=new AdminStore(directory.resolve("db"));var service=ReliableMatchingService.open(journal,store,restored)){
            assertCounter(restored,"commands",0);assertCounter(restored,"trades",0);
            assertNull(EngineMetricsTests.pair(restored,"BTC_USDT").get("latestPriceTicks"));
            service.process(order("taker",2,"BTC_USDT",OrderCommand.Side.BUY));
            assertCounter(restored,"commands",0);
            assertNull(EngineMetricsTests.pair(restored,"BTC_USDT").get("latestQuantityLots"));
            service.process(order("new-maker",3,"BTC_USDT",OrderCommand.Side.SELL));
            service.process(order("new-taker",4,"BTC_USDT",OrderCommand.Side.BUY));
            assertCounter(restored,"commands",2);assertCounter(restored,"trades",1);
            assertEquals("100",EngineMetricsTests.pair(restored,"BTC_USDT").get("latestPriceTicks"));

        }
    }
    void assertCounter(EngineMetrics metrics,String name,long expected){var counters=(Map<?,?>)metrics.snapshot(Set.of()).get("counters");assertEquals(expected,((Map<?,?>)counters.get(name)).get("total"));}
}
