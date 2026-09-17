package com.exchange.matching.core.orderbook;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

final class OrderBook {
    long bidOrderCount;
    long askOrderCount;

    // 买盘：价格从高到低
    final NavigableMap<Long, PriceLevel> bids = new TreeMap<>(Comparator.reverseOrder());

    // 卖盘：价格从低到高
    final NavigableMap<Long, PriceLevel> asks = new TreeMap<>();

    // 快速找到订单，用于撤单
    final Map<Long, OrderNode> ordersById = new HashMap<>();
}
