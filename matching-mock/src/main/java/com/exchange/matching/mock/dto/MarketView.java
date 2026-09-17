package com.exchange.matching.mock.dto;

import java.util.List;

public record MarketView(String sequence, List<LevelView> bids, List<LevelView> asks, List<TradeView> trades) {}
