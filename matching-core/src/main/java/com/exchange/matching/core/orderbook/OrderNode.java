package com.exchange.matching.core.orderbook;

import com.exchange.matching.protocol.command.OrderCommand;

final class OrderNode {
    long orderId;
    com.exchange.matching.protocol.command.OrderCommand.Side side;
    long remainingLots;
    PriceLevel level;
    OrderNode previous;
    OrderNode next;
}
