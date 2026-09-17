package com.exchange.matching.server.service;
import com.exchange.matching.protocol.model.Trade;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class EngineMetricsTests {
    static final class MutableClock extends Clock {
        long millis=100000;
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return Instant.ofEpochMilli(millis);}
    }
    Trade trade(String symbol,long price,long quantity){return new Trade("trade",symbol,1,2,price,quantity);}
    @Test void ratesExpireWhenTrafficStops(){
        var clock=new MutableClock();var metrics=new EngineMetrics(clock);
        metrics.processed("BTC_USDT",List.of(trade("BTC_USDT",100,5)));metrics.event("rabbitReceived");
        clock.millis+=1000;
        var counters=(Map<?,?>)metrics.snapshot(Set.of()).get("counters");
        assertEquals(1L,((Map<?,?>)counters.get("commands")).get("perSecond"));
        assertEquals(1L,((Map<?,?>)counters.get("commands")).get("total"));
        assertEquals(1L,((Map<?,?>)counters.get("trades")).get("perSecond"));
        clock.millis+=61000;
        counters=(Map<?,?>)metrics.snapshot(Set.of()).get("counters");
        assertEquals(0L,((Map<?,?>)counters.get("commands")).get("perSecond"));
        assertEquals(1L,((Map<?,?>)counters.get("commands")).get("total"));
        assertEquals(60,((List<?>)metrics.snapshot(Set.of()).get("history")).size());
    }
    @Test void latestTradeUsesLastFillPreservesLongPrecisionAndIgnoresUnfilledCommands(){
        var metrics=new EngineMetrics();
        assertNull(pair(metrics,"BTC_USDT").get("latestPriceTicks"));
        assertNull(pair(metrics,"BTC_USDT").get("latestQuantityLots"));
        metrics.processed("BTC_USDT",List.of(trade("BTC_USDT",100,5),trade("BTC_USDT",Long.MAX_VALUE,Long.MAX_VALUE-1)));
        metrics.processed("BTC_USDT",List.of());
        assertEquals("9223372036854775807",pair(metrics,"BTC_USDT").get("latestPriceTicks"));
        assertEquals("9223372036854775806",pair(metrics,"BTC_USDT").get("latestQuantityLots"));
        assertNull(pair(metrics,"ETH_USDT").get("latestPriceTicks"));
        metrics.processed("ETH_USDT",List.of(trade("ETH_USDT",200,8)));
        assertEquals("200",pair(metrics,"ETH_USDT").get("latestPriceTicks"));
        assertEquals("9223372036854775807",pair(metrics,"BTC_USDT").get("latestPriceTicks"));
    }
    @Test void peaksExcludeIncompleteSecondsAndRemainAfterHistoryWrapsWithoutPolling(){
        var clock=new MutableClock();var metrics=new EngineMetrics(clock);
        for(int i=0;i<5;i++)metrics.processed("BTC_USDT",List.of(trade("BTC_USDT",100,1),trade("BTC_USDT",100,2)));
        assertEquals(0L,counter(metrics,"commands").get("maxPerSecond"));
        clock.millis+=1000;
        assertEquals(5L,counter(metrics,"commands").get("maxPerSecond"));
        assertEquals(10L,counter(metrics,"trades").get("maxPerSecond"));
        for(int i=0;i<8;i++)metrics.processed("ETH_USDT",List.of());
        assertEquals(5L,counter(metrics,"commands").get("maxPerSecond"));
        clock.millis+=61000;
        metrics.processed("BTC_USDT",List.of());
        assertEquals(8L,counter(metrics,"commands").get("maxPerSecond"));
        assertEquals(0L,counter(metrics,"commands").get("perSecond"));
        assertEquals(10L,counter(metrics,"trades").get("maxPerSecond"));
        assertEquals(5L,((Map<?,?>)pair(metrics,"BTC_USDT").get("commands")).get("maxPerSecond"));
        assertEquals(8L,((Map<?,?>)pair(metrics,"ETH_USDT").get("commands")).get("maxPerSecond"));
        assertEquals(0L,((Map<?,?>)pair(metrics,"SOL_USDT").get("commands")).get("maxPerSecond"));
        assertEquals(0L,counter(new EngineMetrics(clock),"commands").get("maxPerSecond"));
    }
    @Test void messagePeaksAreIndependentAndSurviveIdleTimeWithoutPolling(){
        var clock=new MutableClock();var metrics=new EngineMetrics(clock);
        for(int i=0;i<7;i++)metrics.event("rabbitReceived");
        for(int i=0;i<3;i++)metrics.event("kafkaPublished");
        clock.millis+=61000;
        metrics.event("rabbitReceived");
        assertEquals(7L,counter(metrics,"rabbitReceived").get("maxPerSecond"));
        assertEquals(3L,counter(metrics,"kafkaPublished").get("maxPerSecond"));
        assertEquals(0L,counter(metrics,"rabbitAcked").get("maxPerSecond"));
        assertEquals(0L,counter(metrics,"rabbitReceived").get("perSecond"));
    }
    static Map<?,?> counter(EngineMetrics metrics,String name){
        return (Map<?,?>)((Map<?,?>)metrics.snapshot(Set.of()).get("counters")).get(name);
    }
    static Map<?,?> pair(EngineMetrics metrics,String symbol){
        return ((List<?>)metrics.snapshot(Set.of(symbol)).get("pairs")).stream().map(item->(Map<?,?>)item)
                .filter(item->symbol.equals(item.get("symbol"))).findFirst().orElseThrow();
    }
}
