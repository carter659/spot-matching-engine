package com.exchange.matching.mock.dto;

import com.exchange.matching.protocol.command.OrderCommand;
import com.exchange.matching.protocol.model.OrderType;
import com.exchange.matching.protocol.model.TimeInForce;
import java.math.BigDecimal;

public record PlaceRequest(String commandId, String symbol, OrderCommand.Side side,
                               OrderType orderType, TimeInForce timeInForce, BigDecimal price, BigDecimal quantity) {}
