package com.exchange.matching.core.orderbook;

import com.exchange.matching.protocol.command.OrderCommand;
import com.exchange.matching.protocol.model.BookSnapshot;
import com.exchange.matching.protocol.model.Trade;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static com.exchange.matching.protocol.command.OrderCommand.Action.*;
import static com.exchange.matching.protocol.command.OrderCommand.Side.*;
import static com.exchange.matching.protocol.event.OrderResult.Status.*;

class MatchingEngineTests {
    @Test void refreshExposesRealBookWithoutAdvancingSequenceOrChangingOrders() {
        var engine = new MatchingEngine();
        var placed = engine.process(order("refresh-place", 91, BUY, 100, 7));
        var first = engine.marketSnapshots().getFirst();
        assertEquals(placed.market().orderBook(), first.orderBook());
        assertEquals(first, engine.marketSnapshots().getFirst());
        assertEquals(1, engine.bookDepths().get("BTC_USDT").bidOrderCount());
    }
    private OrderCommand order(String id, long order, OrderCommand.Side side, long price, long qty) {
        return new OrderCommand(id, PLACE, order, "BTC_USDT", side, price, qty);
    }
    @Test void matchesBestPriceThenFifoAndCancelsRemainder() {
        var engine = new MatchingEngine();
        engine.process(order("s1", 1, SELL, 101, 3));
        engine.process(order("s2", 2, SELL, 100, 2));
        engine.process(order("s3", 3, SELL, 100, 4));
        var matched = engine.process(order("b1", 4, BUY, 101, 5));
        assertEquals(List.of(2L, 3L), matched.result().trades().stream().map(Trade::makerOrderId).toList());
        assertEquals(List.of(2L, 3L), matched.result().trades().stream().map(Trade::quantityLots).toList());
        assertEquals(FILLED, matched.result().status());
        assertEquals(List.of(new BookSnapshot.Level(100, 1), new BookSnapshot.Level(101, 3)),
                matched.market().orderBook().asks());
        var cancelled = engine.process(new OrderCommand("c1", CANCEL, 3, "BTC_USDT", null, 0, 0));
        assertEquals(CANCELLED, cancelled.result().status());
        assertEquals(1, cancelled.result().cancelledLots());
        assertEquals(matched.result().trades(), cancelled.market().latestTrades());
        assertEquals(List.of(new BookSnapshot.Level(101, 3)), cancelled.market().orderBook().asks());
    }
    @Test void sellMatchesHighestBidAndLeavesPartialOrder() {
        var engine = new MatchingEngine();
        engine.process(order("b1", 1, BUY, 100, 2));
        engine.process(order("b2", 2, BUY, 102, 3));
        var result = engine.process(order("s1", 3, SELL, 101, 5));
        assertEquals(PARTIALLY_FILLED, result.result().status());
        assertEquals(102, result.result().trades().getFirst().priceTicks());
        assertEquals(2, result.result().remainingLots());
        assertEquals(List.of(new BookSnapshot.Level(100, 2)), result.market().orderBook().bids());
    }
    @Test void redeliveryIsIdempotentAndConflictingCommandIdIsRejected() {
        var engine = new MatchingEngine();
        var command = order("one", 1, BUY, 100, 5);
        var result = engine.process(command);
        assertEquals(result, engine.process(command));
        assertThrows(IllegalArgumentException.class, () -> engine.process(order("one", 2, BUY, 100, 5)));
        assertEquals(REJECTED, engine.process(order("two", 1, BUY, 100, 5)).result().status());
    }
    @Test void cancelMiddleNodePreservesFifo() {
        var engine = new MatchingEngine();
        for (int i = 1; i <= 3; i++) engine.process(order("s" + i, i, SELL, 100, 1));
        engine.process(new OrderCommand("cancel", CANCEL, 2, "BTC_USDT", null, 0, 0));
        var result = engine.process(order("buy", 4, BUY, 100, 2));
        assertEquals(List.of(1L, 3L), result.result().trades().stream().map(Trade::makerOrderId).toList());
        assertTrue(result.market().orderBook().asks().isEmpty());
    }
    @Test void rejectsOverflowWithoutDamagingBook() {
        var engine = new MatchingEngine();
        engine.process(order("big", 1, BUY, 100, Long.MAX_VALUE));
        var rejected = engine.process(order("overflow", 2, BUY, 100, 1));
        assertEquals(REJECTED, rejected.result().status());
        assertEquals(Long.MAX_VALUE, rejected.market().orderBook().bids().getFirst().totalRemainingLots());
    }
}
