package com.exchange.matching.mock.dto;
public record BookStrategyRequest(String symbol, int depth, java.math.BigDecimal quantityMultiplier) {
    public BookStrategyRequest(String symbol, int depth) { this(symbol, depth, java.math.BigDecimal.ONE); }
    public BookStrategyRequest { if (quantityMultiplier == null) quantityMultiplier = java.math.BigDecimal.ONE; }
}
