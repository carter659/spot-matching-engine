package com.exchange.matching.protocol.event;

import com.exchange.matching.protocol.model.Trade;

import java.util.List;

public record OrderResult(String commandId, long orderId, String symbol, Status status,
                          long remainingLots, long cancelledLots, String reason, List<Trade> trades) {
    public enum Status { OPEN, PARTIALLY_FILLED, FILLED, CANCELLED, EXPIRED, REJECTED }
    public OrderResult { trades = List.copyOf(trades); }
}
