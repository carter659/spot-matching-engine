package com.exchange.matching.mock.market;

import com.exchange.matching.protocol.model.Trade;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MarketTradeHistoryTests {
    Trade trade(String id) { return new Trade(id, "BTC_USDT", 1, 2, 6500000, 1000); }
    @Test void newestFirstDeduplicatesSnapshotsAndRetainsTradesAcrossEmptyUpdates() {
        var history = new MarketTradeHistory();
        history.record(List.of(trade("one")));
        var firstTime = history.latest("BTC_USDT").getFirst().receivedAt();
        history.record(List.of(trade("one"), trade("two")));
        history.record(List.of());
        assertEquals(List.of("two", "one"), history.latest("BTC_USDT").stream().map(t -> t.trade().tradeId()).toList());
        assertEquals(firstTime, history.latest("BTC_USDT").getLast().receivedAt());
    }
    @Test void keepsOnlyMostRecentHundredTrades() {
        var history = new MarketTradeHistory();
        for (int i = 0; i < 120; i++) history.record(List.of(trade("trade-" + i)));
        assertEquals(100, history.latest("BTC_USDT").size());
        assertEquals("trade-119", history.latest("BTC_USDT").getFirst().trade().tradeId());
        assertEquals("trade-20", history.latest("BTC_USDT").getLast().trade().tradeId());
    }
    @Test void repeatedOversizeBatchDoesNotChangeReceiptTimes() {
        var history = new MarketTradeHistory();
        var batch = java.util.stream.IntStream.range(0, 120).mapToObj(i -> trade("trade-" + i)).toList();
        history.record(batch);
        var first = history.latest("BTC_USDT");
        history.record(batch);
        assertEquals(first, history.latest("BTC_USDT"));
    }
}
