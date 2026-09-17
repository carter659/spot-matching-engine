package com.exchange.matching.server.service;
import org.springframework.stereotype.Component;
import com.exchange.matching.protocol.model.Trade;
import java.time.Clock;
import java.util.*;
import java.util.function.IntSupplier;

/** In-memory statistics for this process only, with a bounded 60 second rate history. */
@Component
public final class EngineMetrics {
    private final Clock clock;
    private final long startedAt;
    private final Map<String, Counter> counters = new LinkedHashMap<>();
    private final Map<String, Counter[]> pairs = new TreeMap<>();
    private final Map<String, Trade> latestTrades = new HashMap<>();
    private IntSupplier backlog = () -> 0;
    public EngineMetrics() { this(Clock.systemUTC()); }
    public EngineMetrics(Clock clock) {
        this.clock=clock; startedAt=clock.millis();
        for (String name : List.of("commands", "trades", "rabbitReceived", "rabbitAcked", "rabbitRetried", "rabbitRejected", "rabbitPublished", "rabbitPublishFailed", "kafkaPublished", "kafkaFailed", "kafkaDropped")) counters.put(name,new Counter());
    }
    private static final class Counter {
        long total;
        long completedPeak, activeSecond = Long.MIN_VALUE, activeCount;
        final long[] seconds = new long[61], counts = new long[61];
        Counter() { Arrays.fill(seconds,Long.MIN_VALUE); }
        void add(long second, long count) {
            total += count;
            // Preserve lifetime peaks even when nobody polls and the history ring wraps.
            if (second != activeSecond) {
                completedPeak = Math.max(completedPeak, activeCount);
                activeSecond = second;
                activeCount = 0;
            }
            activeCount += count;
            int i=Math.floorMod(second,61);
            if(seconds[i]!=second){seconds[i]=second;counts[i]=0;}
            counts[i]+=count;
        }
        long at(long second) { int i=Math.floorMod(second,61); return seconds[i]==second?counts[i]:0; }
        Map<String,Long> snapshot(long second) { return Map.of("total",total,"perSecond",at(second),
                "maxPerSecond",Math.max(completedPeak,activeSecond<=second?activeCount:0)); }
    }
    public synchronized void processed(String symbol, List<Trade> trades) {
        long second=clock.millis()/1000;
        counters.get("commands").add(second,1); counters.get("trades").add(second,trades.size());
        var pair=pairs.computeIfAbsent(symbol,ignored->new Counter[]{new Counter(),new Counter()});
        pair[0].add(second,1);pair[1].add(second,trades.size());
        if (!trades.isEmpty()) latestTrades.put(symbol,trades.getLast());
    }
    public synchronized void event(String name) { counters.get(name).add(clock.millis()/1000,1); }
    public synchronized void backlog(IntSupplier source) { backlog=source; }
    public synchronized Map<String,Object> snapshot(Set<String> enabled) { return snapshot(enabled,Map.of()); }
    public synchronized Map<String,Object> snapshot(Set<String> enabled,
            Map<String,com.exchange.matching.core.orderbook.OrderBookDepth> depths) {
        long second=clock.millis()/1000-1;
        var rates=new LinkedHashMap<String,Object>(); counters.forEach((name,counter)->rates.put(name,counter.snapshot(second)));
        var symbols=new TreeSet<>(pairs.keySet());symbols.addAll(enabled);
        var rows=new ArrayList<Map<String,Object>>();
        for(var symbol:symbols){
            var pair=pairs.getOrDefault(symbol,new Counter[]{new Counter(),new Counter()});
            var trade=latestTrades.get(symbol);
            var row=new LinkedHashMap<String,Object>();
            row.put("symbol",symbol);row.put("enabled",enabled.contains(symbol));
            row.put("commands",pair[0].snapshot(second));row.put("trades",pair[1].snapshot(second));
            // Decimal strings preserve all 64-bit integer digits in browser JSON parsing.
            row.put("latestPriceTicks",trade==null?null:Long.toString(trade.priceTicks()));
            row.put("latestQuantityLots",trade==null?null:Long.toString(trade.quantityLots()));
            var depth=depths.getOrDefault(symbol,com.exchange.matching.core.orderbook.OrderBookDepth.EMPTY);
            row.put("bidOrderCount",depth.bidOrderCount());row.put("bidLevelCount",depth.bidLevelCount());
            row.put("askOrderCount",depth.askOrderCount());row.put("askLevelCount",depth.askLevelCount());
            rows.add(row);
        }
        var history=new ArrayList<Map<String,Long>>();
        for(long s=second-59;s<=second;s++) history.add(Map.of("second",s,"commands",counters.get("commands").at(s),"trades",counters.get("trades").at(s),"received",counters.get("rabbitReceived").at(s),"published",counters.get("rabbitPublished").at(s)+counters.get("kafkaPublished").at(s)));
        return Map.of("sampleSecond",second,"startedAt",startedAt,"uptimeSeconds",(clock.millis()-startedAt)/1000,"counters",rates,"pairs",rows,"history",history,"marketBacklog",backlog.getAsInt());
    }
}
