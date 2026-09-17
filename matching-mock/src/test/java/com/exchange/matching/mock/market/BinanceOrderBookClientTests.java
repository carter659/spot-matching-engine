package com.exchange.matching.mock.market;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;

class BinanceOrderBookClientTests {
    @Test @EnabledIfSystemProperty(named = "binance.live", matches = "true")
    void readsPublicBookWithoutCredentials() throws Exception {
        var book = new BinanceOrderBookClient().read("BTC_USDT",5);
        assertTrue(book.lastUpdateId().matches("[0-9]+"));
        assertEquals(5,book.bids().size()); assertEquals(5,book.asks().size());
        assertEquals(2,book.bids().getFirst().size());
    }
    @Test void rejectsInvalidSymbolBeforeNetworkAccess() {
        var client = new BinanceOrderBookClient();
        assertThrows(IllegalArgumentException.class, () -> client.read("BTC/USDT?x=y",5));
        assertThrows(IllegalArgumentException.class, () -> client.read("BTC_USDT",1000));
    }
}
