package com.exchange.matching.protocol.event;

import com.exchange.matching.protocol.model.BookSnapshot;
import com.exchange.matching.protocol.model.Trade;

import java.util.List;

public record MarketUpdate(String commandId, BookSnapshot orderBook, List<Trade> latestTrades) {
    public MarketUpdate { latestTrades = List.copyOf(latestTrades); }
}
