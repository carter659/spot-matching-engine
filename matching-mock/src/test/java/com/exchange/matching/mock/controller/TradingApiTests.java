package com.exchange.matching.mock.controller;

import com.exchange.matching.mock.dto.PlaceRequest;
import com.exchange.matching.mock.dto.CancelRequest;

import com.exchange.matching.core.orderbook.MatchingEngine;
import com.exchange.matching.mock.market.MarketTradeHistory;
import com.exchange.matching.mock.repository.MockInbox;
import com.exchange.matching.mock.repository.MockOutbox;
import com.exchange.matching.mock.service.TradingService;
import com.exchange.matching.persistence.journal.DurableJournal;
import com.exchange.matching.protocol.command.OrderCommand;
import com.exchange.matching.protocol.event.MarketUpdate;
import com.exchange.matching.protocol.event.OrderResult;
import com.exchange.matching.protocol.model.OrderType;
import com.exchange.matching.protocol.model.TimeInForce;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class TradingApiTests {
    @Test void exposesTwentyPairsAndFiltersOrdersBySymbolWithExactLowPricePrecision() throws Exception {
        mvc.perform(get("/mock/trading/pairs")).andExpect(jsonPath("$.length()").value(20));
        submit(order("btc", "BUY", "LIMIT", "GTC", "100.00", "0.1000"));
        submit(order("shib", "BUY", "LIMIT", "GTC", "0.00002001", "10.0000").replace("BTC_USDT", "SHIB_USDT"));
        mvc.perform(get("/mock/trading").param("symbol", "SHIB_USDT"))
                .andExpect(jsonPath("$.baseAsset").value("SHIB"))
                .andExpect(jsonPath("$.priceScale").value(8))
                .andExpect(jsonPath("$.orders.length()").value(1))
                .andExpect(jsonPath("$.orders[0].price").value("0.00002001"))
                .andExpect(jsonPath("$.orders[0].status").value("REJECTED"));
        assertEquals(2001, outbox.entries().stream().filter(e -> e.command().symbol().equals("SHIB_USDT"))
                .findFirst().orElseThrow().command().priceTicks());
        mvc.perform(get("/mock/trading")).andExpect(jsonPath("$.orders.length()").value(1));
        mvc.perform(get("/mock/trading").param("symbol", "FAKE_USDT")).andExpect(status().isBadRequest());
    }

    @Test void cancellationUsesOriginalNonBitcoinSymbol() throws Exception {
        submit(order("eth", "BUY", "LIMIT", "GTC", "100.00", "0.1000").replace("BTC_USDT", "ETH_USDT"));
        var id = service.state("ETH_USDT").orders().getFirst().orderId();
        service.cancel(Long.parseLong(id), new CancelRequest("eth-cancel"));
        assertEquals("ETH_USDT", outbox.entries().getLast().command().symbol());
    }
    @TempDir Path directory;
    MockOutbox outbox;
    MockInbox inbox;
    TradingService service;
    MarketTradeHistory history;
    MockMvc mvc;

    @BeforeEach void setup() throws Exception {
        var engine = new MatchingEngine();
        Map<String, MarketUpdate> market = new HashMap<>();
        history = new MarketTradeHistory();
        inbox = new MockInbox(new DurableJournal<>(directory.resolve("inbox"), OrderResult.class));
        outbox = new MockOutbox(new DurableJournal<>(directory.resolve("outbox"), MockOutbox.Entry.class), command -> {
            var outcome = engine.process(command);
            try { inbox.store(outcome.result()); }
            catch (java.io.IOException error) { throw new UncheckedIOException(error); }
            market.put(command.symbol(), outcome.market());
            history.record(outcome.market().latestTrades());
        });
        service = new TradingService(outbox, inbox, market, history);
        mvc = MockMvcBuilders.standaloneSetup(new TradingController(service)).build();
    }
    @AfterEach void close() throws Exception { outbox.close(); inbox.close(); }
    private String order(String id, String side, String type, String tif, String price, String qty) {
        return """
                {"commandId":"%s","symbol":"BTC_USDT","side":"%s","orderType":"%s",
                 "timeInForce":"%s","price":%s,"quantity":"%s"}
                """.formatted(id, side, type, tif, price == null ? "null" : "\"" + price + "\"", qty);
    }
    private void submit(String json) throws Exception {
        mvc.perform(post("/mock/trading/orders").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.orderId").isString())
                .andExpect(jsonPath("$.status").value("BROKER_CONFIRMED"));
    }
    @Test void pageFlowUpdatesMakerFillAndCancelsOnlyRemainder() throws Exception {
        submit(order("maker", "SELL", "LIMIT", "GTC", "65000.01", "0.3000"));
        submit(order("taker", "BUY", "MARKET", "IOC", null, "0.1000"));
        var maker = service.state().orders().stream().filter(o -> o.commandId().equals("maker")).findFirst().orElseThrow();
        assertEquals("0.1000", maker.filledQuantity());
        assertEquals("0.2000", maker.remainingQuantity());
        assertEquals("PARTIALLY_FILLED", maker.status());
        assertTrue(maker.canCancel());
        mvc.perform(post("/mock/trading/orders/" + maker.orderId() + "/cancel")
                .contentType(MediaType.APPLICATION_JSON).content("{\"commandId\":\"cancel-maker\"}"))
                .andExpect(status().isAccepted());
        mvc.perform(get("/mock/trading")).andExpect(status().isOk())
                .andExpect(jsonPath("$.orders[1].status").value("CANCELLED"))
                .andExpect(jsonPath("$.orders[1].filledQuantity").value("0.1000"))
                .andExpect(jsonPath("$.orders[1].cancelledQuantity").value("0.2000"))
                .andExpect(jsonPath("$.orders[1].remainingQuantity").value("0.0000"))
                .andExpect(jsonPath("$.recentTrades[0].price").value("65000.01"))
                .andExpect(jsonPath("$.recentTrades[0].quantity").value("0.1000"))
                .andExpect(jsonPath("$.recentTrades[0].makerOrderId").isString())
                .andExpect(jsonPath("$.recentTrades[0].receivedAt").isString())
                .andExpect(jsonPath("$.market.asks").isEmpty());
    }
    @Test void decimalsAreExactAndOriginalRequestIsIdempotent() throws Exception {
        String payload = order("same", "BUY", "LIMIT", "GTC", "12345.67", "0.1234");
        submit(payload); submit(payload);
        assertEquals(1, outbox.entries().size());
        assertEquals(1234567, outbox.entries().getFirst().command().priceTicks());
        assertEquals(1234, outbox.entries().getFirst().command().quantityLots());
        assertEquals("12345.67", service.state().orders().getFirst().price());
    }
    @Test void iocAndFokReturnCancellationInState() throws Exception {
        submit(order("maker", "SELL", "LIMIT", "GTC", "100.00", "0.1000"));
        submit(order("fok", "BUY", "LIMIT", "FOK", "100.00", "0.2000"));
        assertEquals("EXPIRED", service.state().orders().getFirst().status());
        assertEquals("0.0000", service.state().orders().getFirst().filledQuantity());
        submit(order("ioc", "BUY", "LIMIT", "IOC", "100.00", "0.2000"));
        assertEquals("0.1000", service.state().orders().getFirst().filledQuantity());
        assertEquals("0.1000", service.state().orders().getFirst().cancelledQuantity());
        assertFalse(service.state().orders().getFirst().canCancel());
    }
    @Test void precisionAndUnsupportedMarketPolicyAreRejectedBeforePublish() throws Exception {
        for (String payload : List.of(order("precision", "BUY", "LIMIT", "GTC", "100.001", "0.1000"),
                order("policy", "BUY", "MARKET", "FOK", null, "0.1000"),
                order("quantity", "BUY", "LIMIT", "GTC", "100.00", "0.00001"))) {
            mvc.perform(post("/mock/trading/orders").contentType(MediaType.APPLICATION_JSON).content(payload))
                    .andExpect(status().isBadRequest());
        }
        assertTrue(outbox.entries().isEmpty());
    }
    @Test void publishFailureIsRetryableAndNeverShownAsFilled() throws Exception {
        try (var offline = new MockOutbox(new DurableJournal<>(directory.resolve("offline"), MockOutbox.Entry.class),
                command -> { throw new IllegalStateException("broker unavailable"); })) {
            var offlineService = new TradingService(offline, inbox, Map.of(), history);
            var api = MockMvcBuilders.standaloneSetup(new TradingController(offlineService)).build();
            api.perform(post("/mock/trading/orders").contentType(MediaType.APPLICATION_JSON)
                    .content(order("offline", "BUY", "LIMIT", "GTC", "100.00", "0.1000")))
                    .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.retryable").value(true));
            api.perform(get("/mock/trading")).andExpect(jsonPath("$.orders[0].status").value("PENDING_PUBLISH"))
                    .andExpect(jsonPath("$.orders[0].canCancel").value(false));
        }
    }
    @Test void pendingCancelIsNotTreatedAsCancelled() throws Exception {
        // A confirmed publisher can return before the asynchronous matching result arrives.
        outbox.close();
        outbox = new MockOutbox(new DurableJournal<>(directory.resolve("outbox"), MockOutbox.Entry.class), c -> {});
        var delayed = new TradingService(outbox, inbox, Map.of(), history);
        var receipt = delayed.place(new PlaceRequest("waiting", "BTC_USDT", OrderCommand.Side.BUY,
                OrderType.LIMIT, TimeInForce.GTC, new java.math.BigDecimal("100"), new java.math.BigDecimal("1")));
        assertEquals("BROKER_CONFIRMED", delayed.state().orders().getFirst().status());
        inbox.store(new OrderResult("waiting", Long.parseLong(receipt.orderId()), "BTC_USDT",
                OrderResult.Status.OPEN, 10000, 0, null, List.of()));
        delayed.cancel(Long.parseLong(receipt.orderId()), new CancelRequest("cancel-waiting"));
        var order = delayed.state().orders().getFirst();
        assertTrue(order.cancelPending());
        assertFalse(order.canCancel());
        assertEquals("OPEN", order.status());
        assertEquals("1.0000", order.remainingQuantity());
    }
}
