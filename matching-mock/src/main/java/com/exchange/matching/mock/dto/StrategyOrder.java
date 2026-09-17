package com.exchange.matching.mock.dto;
import com.exchange.matching.protocol.command.OrderCommand.Side;
import java.math.BigDecimal;
public record StrategyOrder(String commandId, Side side, BigDecimal price, BigDecimal quantity) {}
