package com.exchange.matching.protocol.command;

import com.exchange.matching.protocol.model.OrderType;
import com.exchange.matching.protocol.model.TimeInForce;

public record OrderCommand(String commandId, Action action, long orderId, String symbol,
                           Side side, long priceTicks, long quantityLots,
                           OrderType orderType, TimeInForce timeInForce) {
    public enum Action { PLACE, CANCEL }
    public enum Side { BUY, SELL }

    // Preserve old commands and journal records as LIMIT/GTC.
    public OrderCommand {
        if (orderType == null) orderType = OrderType.LIMIT;
        if (timeInForce == null) timeInForce = orderType == OrderType.MARKET ? TimeInForce.IOC : TimeInForce.GTC;
    }

    public OrderCommand(String commandId, Action action, long orderId, String symbol,
                        Side side, long priceTicks, long quantityLots) {
        this(commandId, action, orderId, symbol, side, priceTicks, quantityLots, OrderType.LIMIT, TimeInForce.GTC);
    }

    public void validate() {
        if (commandId == null || commandId.isBlank() || commandId.length() > 128
                || action == null || orderId <= 0 || symbol == null
                || !symbol.matches("[A-Z0-9_-]{1,32}")) {
            throw new IllegalArgumentException("Invalid command identity or symbol");
        }
        if (action == Action.PLACE) {
            if (side == null || quantityLots <= 0) {
                throw new IllegalArgumentException("Order requires side and positive quantity");
            }
            if (orderType == OrderType.LIMIT && priceTicks <= 0) {
                throw new IllegalArgumentException("Limit order requires a positive price");
            }
            if (orderType == OrderType.MARKET && (priceTicks != 0 || timeInForce != TimeInForce.IOC)) {
                throw new IllegalArgumentException("Market orders require priceTicks=0 and timeInForce=IOC");
            }
        }
    }
}
