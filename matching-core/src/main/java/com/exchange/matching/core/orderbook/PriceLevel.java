package com.exchange.matching.core.orderbook;

final class PriceLevel {
    long priceTicks;
    long totalRemainingLots;

    OrderNode head;
    OrderNode tail;
}
