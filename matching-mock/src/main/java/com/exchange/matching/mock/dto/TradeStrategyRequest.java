package com.exchange.matching.mock.dto;
import java.math.BigDecimal;
public record TradeStrategyRequest(String symbol, BigDecimal quantityMultiplier, int maxPerSecond, BigDecimal maxQuantity, int maxAgeSeconds, String mode, BigDecimal targetPrice, String sweepSide) {
 public TradeStrategyRequest(String symbol,BigDecimal multiplier,int rate,BigDecimal max,int age){this(symbol,multiplier,rate,max,age,"FOLLOW",null,null);}
}
