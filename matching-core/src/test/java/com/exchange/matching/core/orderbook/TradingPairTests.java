package com.exchange.matching.core.orderbook;

import com.exchange.matching.protocol.command.OrderCommand;
import com.exchange.matching.protocol.event.OrderResult;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class TradingPairTests {
    OrderCommand order(String id, long orderId, String symbol, OrderCommand.Side side, long quantity) {
        return new OrderCommand(id, OrderCommand.Action.PLACE, orderId, symbol, side, 100, quantity);
    }
    @Test void rejectsDisabledSymbolAndKeepsBooksIsolated() {
        var engine = new MatchingEngine();
        assertEquals(OrderResult.Status.REJECTED, engine.process(order("off", 1, "ETH_USDT", OrderCommand.Side.BUY, 10)).result().status());
        engine.configure(new EngineParameters(Set.of("BTC_USDT", "ETH_USDT"), 1, 100));
        engine.process(order("btc-sell", 2, "BTC_USDT", OrderCommand.Side.SELL, 10));
        var eth = engine.process(order("eth-buy", 3, "ETH_USDT", OrderCommand.Side.BUY, 10));
        assertEquals(OrderResult.Status.OPEN, eth.result().status());
        assertTrue(eth.result().trades().isEmpty());
        assertTrue(eth.market().orderBook().asks().isEmpty());
        assertEquals(OrderResult.Status.FILLED, engine.process(order("btc-buy", 4, "BTC_USDT", OrderCommand.Side.BUY, 10)).result().status());
    }
    @Test void removalRequiresCancellingOpenOrdersButQuantityLimitsDoNotBlockCancellation() {
        var engine = new MatchingEngine();
        engine.process(order("open", 1, "BTC_USDT", OrderCommand.Side.BUY, 10));
        assertThrows(IllegalArgumentException.class, () -> engine.configure(new EngineParameters(Set.of("ETH_USDT"), 1, 100)));
        engine.configure(new EngineParameters(Set.of("BTC_USDT"), 20, 30));
        assertEquals(OrderResult.Status.REJECTED, engine.process(order("small", 2, "BTC_USDT", OrderCommand.Side.BUY, 10)).result().status());
        var cancel = new OrderCommand("cancel", OrderCommand.Action.CANCEL, 1, "BTC_USDT", null, 0, 0);
        assertEquals(OrderResult.Status.CANCELLED, engine.process(cancel).result().status());
        engine.configure(new EngineParameters(Set.of("ETH_USDT"), 1, 100));
    }
    @Test void validatesCatalogAndLimits() {
        assertEquals(20, com.exchange.matching.protocol.model.TradingPair.CATALOG.size());
        assertThrows(IllegalArgumentException.class, () -> new EngineParameters(Set.of(), 1, 100));
        assertEquals(Set.of("CUSTOM_USDT"), new EngineParameters(Set.of("custom/usdt"), 1, 100).symbols());
        assertThrows(IllegalArgumentException.class, () -> new EngineParameters(Set.of("BAD SYMBOL"), 1, 100));
        assertThrows(IllegalArgumentException.class, () -> new EngineParameters(Set.of("BTC_USDT"), 100, 1));
    }
}
