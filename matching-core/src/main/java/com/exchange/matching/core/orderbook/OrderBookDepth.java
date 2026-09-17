package com.exchange.matching.core.orderbook;

/** Current resting order counts (not quantities) and nonempty price levels. */
public record OrderBookDepth(long bidOrderCount, int bidLevelCount, long askOrderCount, int askLevelCount) {
    public static final OrderBookDepth EMPTY = new OrderBookDepth(0, 0, 0, 0);
}
