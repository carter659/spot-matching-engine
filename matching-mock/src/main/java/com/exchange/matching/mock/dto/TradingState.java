package com.exchange.matching.mock.dto;

import java.util.List;

public record TradingState(String symbol, String baseAsset, String quoteAsset, int priceScale,
                               int quantityScale, List<OrderView> orders, MarketView market,
                               List<TradeView> recentTrades, long pendingCommandCount, String observedAt) {}
