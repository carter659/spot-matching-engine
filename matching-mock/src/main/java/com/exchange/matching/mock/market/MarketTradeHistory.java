package com.exchange.matching.mock.market;

import com.exchange.matching.protocol.model.Trade;

import java.time.Instant;
import java.util.*;

/** Best-effort, bounded Kafka trade tape; this is not a settlement ledger. */
public final class MarketTradeHistory {
    public static final int CAPACITY = 100;
    public record ReceivedTrade(Trade trade, Instant receivedAt) {}
    private final Map<String, LinkedHashMap<String, ReceivedTrade>> bySymbol = new HashMap<>();
    private final Map<String, List<String>> previousBatches = new HashMap<>();

    public synchronized void record(List<Trade> trades) {
        Instant now = Instant.now();
        Map<String, List<Trade>> batches = new HashMap<>();
        for (Trade trade : trades) batches.computeIfAbsent(trade.symbol(), ignored -> new ArrayList<>()).add(trade);
        for (var batch : batches.entrySet()) {
            List<String> ids = batch.getValue().stream().map(Trade::tradeId).toList();
            if (ids.equals(previousBatches.get(batch.getKey()))) continue;
            var history = bySymbol.computeIfAbsent(batch.getKey(), ignored -> new LinkedHashMap<>());
            for (Trade trade : batch.getValue()) {
                history.putIfAbsent(trade.tradeId(), new ReceivedTrade(trade, now));
                while (history.size() > CAPACITY) history.remove(history.keySet().iterator().next());
            }
            previousBatches.put(batch.getKey(), ids);
        }
    }

    public synchronized List<ReceivedTrade> latest(String symbol) {
        var history = bySymbol.get(symbol);
        if (history == null) return List.of();
        var newestFirst = new ArrayList<>(history.values());
        Collections.reverse(newestFirst);
        return List.copyOf(newestFirst);
    }
}
