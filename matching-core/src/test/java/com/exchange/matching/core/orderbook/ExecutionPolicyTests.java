package com.exchange.matching.core.orderbook;

import com.exchange.matching.protocol.command.OrderCommand;
import com.exchange.matching.protocol.model.OrderType;
import com.exchange.matching.protocol.model.TimeInForce;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.exchange.matching.protocol.command.OrderCommand.Side.*;
import static com.exchange.matching.protocol.model.OrderType.*;
import static com.exchange.matching.protocol.model.TimeInForce.*;
import static com.exchange.matching.protocol.event.OrderResult.Status.*;

class ExecutionPolicyTests {
    private OrderCommand order(String id, long orderId, OrderCommand.Side side, long price, long qty,
                               OrderType type, TimeInForce tif) {
        return new OrderCommand(id, OrderCommand.Action.PLACE, orderId, "BTC_USDT", side, price, qty, type, tif);
    }
    @Test void iocRespectsPriceAndNeverRests() {
        var engine = new MatchingEngine();
        engine.process(order("s1", 1, SELL, 100, 2, LIMIT, GTC));
        engine.process(order("s2", 2, SELL, 102, 3, LIMIT, GTC));
        var outcome = engine.process(order("buy", 3, BUY, 101, 4, LIMIT, IOC));
        assertEquals(EXPIRED, outcome.result().status());
        assertEquals(2, outcome.result().trades().getFirst().quantityLots());
        assertEquals(2, outcome.result().cancelledLots());
        assertEquals(0, outcome.result().remainingLots());
        assertTrue(outcome.market().orderBook().bids().isEmpty());
        assertEquals(102, outcome.market().orderBook().asks().getFirst().priceTicks());
    }
    @Test void fokInsufficientLiquidityDoesNotChangeAnyMaker() {
        var engine = new MatchingEngine();
        engine.process(order("s1", 1, SELL, 100, 2, LIMIT, GTC));
        engine.process(order("s2", 2, SELL, 102, 9, LIMIT, GTC));
        var outcome = engine.process(order("buy", 3, BUY, 101, 3, LIMIT, FOK));
        assertEquals(EXPIRED, outcome.result().status());
        assertEquals(3, outcome.result().cancelledLots());
        assertTrue(outcome.result().trades().isEmpty());
        assertEquals(2, outcome.market().orderBook().asks().getFirst().totalRemainingLots());
        assertEquals(outcome, engine.process(order("buy", 3, BUY, 101, 3, LIMIT, FOK)));
    }
    @Test void fokFillsExactlyAcrossLevels() {
        var engine = new MatchingEngine();
        engine.process(order("s1", 1, SELL, 100, 2, LIMIT, GTC));
        engine.process(order("s2", 2, SELL, 101, 3, LIMIT, GTC));
        var outcome = engine.process(order("buy", 3, BUY, 101, 5, LIMIT, FOK));
        assertEquals(FILLED, outcome.result().status());
        assertEquals(2, outcome.result().trades().size());
        assertTrue(outcome.market().orderBook().asks().isEmpty());
        assertEquals(0, outcome.result().cancelledLots());
    }
    @Test void marketWalksBookAndCancelsUnfilledQuantity() {
        var engine = new MatchingEngine();
        engine.process(order("s1", 1, SELL, 100, 2, LIMIT, GTC));
        engine.process(order("s2", 2, SELL, 200, 3, LIMIT, GTC));
        var outcome = engine.process(order("buy", 3, BUY, 0, 8, MARKET, IOC));
        assertEquals(EXPIRED, outcome.result().status());
        assertEquals(2, outcome.result().trades().size());
        assertEquals(3, outcome.result().cancelledLots());
        assertTrue(outcome.market().orderBook().bids().isEmpty());
        assertTrue(outcome.market().orderBook().asks().isEmpty());
    }
    @Test void marketSellUsesHighestBidAndFullyFills() {
        var engine = new MatchingEngine();
        engine.process(order("b1", 1, BUY, 100, 2, LIMIT, GTC));
        engine.process(order("b2", 2, BUY, 105, 3, LIMIT, GTC));
        var result = engine.process(order("sell", 3, SELL, 0, 3, MARKET, IOC)).result();
        assertEquals(FILLED, result.status());
        assertEquals(105, result.trades().getFirst().priceTicks());
    }
    @Test void emptyBookMarketCancelsWithoutResting() {
        var outcome = new MatchingEngine().process(order("buy", 1, BUY, 0, 1, MARKET, IOC));
        assertEquals(EXPIRED, outcome.result().status());
        assertEquals(1, outcome.result().cancelledLots());
        assertTrue(outcome.market().orderBook().bids().isEmpty());
    }
    @Test void forbidsMarketGtcAndExplicitMarketPrice() {
        assertThrows(IllegalArgumentException.class, () -> order("one", 1, BUY, 0, 1, MARKET, GTC).validate());
        assertThrows(IllegalArgumentException.class, () -> order("one", 1, BUY, 100, 1, MARKET, IOC).validate());
    }
    @Test void fokPreflightDoesNotOverflowOnLargeLevels() {
        var engine = new MatchingEngine();
        engine.process(order("s1", 1, SELL, 100, Long.MAX_VALUE - 1, LIMIT, GTC));
        engine.process(order("s2", 2, SELL, 101, 10, LIMIT, GTC));
        var result = engine.process(order("buy", 3, BUY, 101, Long.MAX_VALUE, LIMIT, FOK));
        assertEquals(FILLED, result.result().status());
        assertEquals(9, result.market().orderBook().asks().getFirst().totalRemainingLots());
    }
}
