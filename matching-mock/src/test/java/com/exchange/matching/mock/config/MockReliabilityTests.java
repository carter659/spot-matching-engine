package com.exchange.matching.mock.config;

import com.exchange.matching.mock.market.MarketTradeHistory;
import com.exchange.matching.mock.repository.MockInbox;
import com.exchange.matching.mock.repository.MockOutbox;
import com.exchange.matching.persistence.journal.DurableJournal;
import com.exchange.matching.protocol.command.OrderCommand;
import com.exchange.matching.protocol.event.MarketUpdate;
import com.exchange.matching.protocol.event.OrderResult;
import com.exchange.matching.protocol.model.BookSnapshot;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.support.MessageBuilder;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MockReliabilityTests {
    @Test void staleKafkaUpdateCannotReinsertOldTrades() {
        var history = new MarketTradeHistory();
        var config = new MockMessagingConfiguration();
        var consumer = config.market(config.latestMarket(), history);
        var newest = new com.exchange.matching.protocol.model.Trade("two", "BTC_USDT", 1, 2, 6500000, 1000);
        var stale = new com.exchange.matching.protocol.model.Trade("one", "BTC_USDT", 1, 2, 6500000, 1000);
        consumer.accept(new MarketUpdate("new", new BookSnapshot("BTC_USDT", 2, List.of(), List.of()), List.of(newest)));
        consumer.accept(new MarketUpdate("old", new BookSnapshot("BTC_USDT", 1, List.of(), List.of()), List.of(stale)));
        assertEquals(List.of("two"), history.latest("BTC_USDT").stream().map(t -> t.trade().tradeId()).toList());
    }
    @TempDir Path directory;
    OrderCommand command() { return new OrderCommand("one", OrderCommand.Action.PLACE, 1, "BTC_USDT", OrderCommand.Side.BUY, 100, 2); }
    OrderResult result() { return new OrderResult("one", 1, "BTC_USDT", OrderResult.Status.OPEN, 2, 0, null, List.of()); }
    @Test void outboxRetriesUnconfirmedCommandAfterRestart() throws Exception {
        Path path = directory.resolve("outbox");
        try (var outbox = new MockOutbox(new DurableJournal<>(path, MockOutbox.Entry.class),
                command -> { throw new IllegalStateException("timeout"); })) {
            assertThrows(IllegalStateException.class, () -> outbox.submit(command()));
            assertEquals(1, outbox.pending().size());
        }
        List<OrderCommand> published = new ArrayList<>();
        try (var outbox = new MockOutbox(new DurableJournal<>(path, MockOutbox.Entry.class), published::add)) {
            outbox.retryPending();
            outbox.submit(command());
            assertEquals(List.of(command()), published);
            assertTrue(outbox.pending().isEmpty());
        }
    }
    @Test void inboxDeduplicatesAcrossRestartAndOnlyThenAcknowledges() throws Exception {
        Path path = directory.resolve("inbox");
        try (var inbox = new MockInbox(new DurableJournal<>(path, OrderResult.class))) { inbox.store(result()); }
        try (var inbox = new MockInbox(new DurableJournal<>(path, OrderResult.class))) {
            Channel channel = mock(Channel.class);
            doAnswer(ignored -> { assertEquals(List.of(result()), inbox.results()); return null; })
                    .when(channel).basicAck(3, false);
            var consumer = new MockMessagingConfiguration().results(inbox);
            consumer.accept(MessageBuilder.withPayload(result()).setHeader(AmqpHeaders.CHANNEL, channel)
                    .setHeader(AmqpHeaders.DELIVERY_TAG, 3L).build());
            verify(channel).basicAck(3, false);
            assertEquals(1, inbox.results().size());
        }
    }
    @Test void inboxFailureNeverAcknowledges() throws Exception {
        MockInbox inbox = mock(MockInbox.class);
        doThrow(new java.io.IOException("disk failure")).when(inbox).store(any());
        Channel channel = mock(Channel.class);
        new MockMessagingConfiguration().results(inbox).accept(MessageBuilder.withPayload(result())
                .setHeader(AmqpHeaders.CHANNEL, channel).setHeader(AmqpHeaders.DELIVERY_TAG, 3L).build());
        verify(channel).basicNack(3, false, true);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }
    @Test void ignoresStaleMarketUpdate() {
        var configuration = new MockMessagingConfiguration();
        Map<String, MarketUpdate> cache = configuration.latestMarket();
        var consumer = configuration.market(cache, new MarketTradeHistory());
        var newest = new MarketUpdate("two", new BookSnapshot("BTC_USDT", 2, List.of(), List.of()), List.of());
        consumer.accept(newest);
        consumer.accept(new MarketUpdate("one", new BookSnapshot("BTC_USDT", 1, List.of(), List.of()), List.of()));
        assertEquals(newest, cache.get("BTC_USDT"));
    }
}
