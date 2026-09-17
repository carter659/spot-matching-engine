package com.exchange.matching.protocol.model;

import java.util.List;

public record BookSnapshot(String symbol, long sequence, List<Level> bids, List<Level> asks) {
    public record Level(long priceTicks, long totalRemainingLots) {}
    public BookSnapshot {
        bids = List.copyOf(bids);
        asks = List.copyOf(asks);
    }
}
