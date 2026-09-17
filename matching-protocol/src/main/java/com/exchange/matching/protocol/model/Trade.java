package com.exchange.matching.protocol.model;

public record Trade(String tradeId, String symbol, long makerOrderId, long takerOrderId,
                    long priceTicks, long quantityLots) {}
