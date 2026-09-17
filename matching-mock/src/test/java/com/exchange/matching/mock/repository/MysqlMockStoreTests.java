package com.exchange.matching.mock.repository;

import com.exchange.matching.protocol.command.OrderCommand;
import com.exchange.matching.protocol.event.OrderResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class MysqlMockStoreTests {
    JdbcTemplate jdbc;
    MysqlMockStore store;
    @BeforeEach void setup() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        store = new MapperTestDatabase(source).store();
    }
    OrderCommand command() {
        return new OrderCommand("place-one", OrderCommand.Action.PLACE, 1, "BTC_USDT", OrderCommand.Side.BUY, 6500000, 1000);
    }
    @Test void persistsBeforePublishAndRetriesAfterRestart() throws Exception {
        try (var outbox = new MockOutbox(store.commands(), command -> {
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM mock_command_log WHERE confirmed=false", Integer.class));
            throw new IllegalStateException("broker unavailable");
        })) {
            assertThrows(IllegalStateException.class, () -> outbox.submit(command()));
        }
        var sent = new ArrayList<OrderCommand>();
        try (var restored = new MockOutbox(store.commands(), sent::add)) {
            assertEquals(List.of(command()), restored.pending());
            restored.retryPending();
        }
        try (var restored = new MockOutbox(store.commands(), sent::add)) {
            restored.submit(command());
            assertTrue(restored.pending().isEmpty());
            assertEquals(List.of(command()), sent);
        }
    }
    @Test void restoresResultsAndDeduplicatesAfterRestart() throws Exception {
        var result = new OrderResult("place-one", 1, "BTC_USDT", OrderResult.Status.OPEN, 1000, 0, null, List.of());
        try (var inbox = new MockInbox(store.results())) { inbox.store(result); }
        try (var inbox = new MockInbox(store.results())) {
            inbox.store(result);
            assertEquals(List.of(result), inbox.results());
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM mock_result_log", Integer.class));
    }
    @Test void failedDatabaseWriteDoesNotPublishOrMarkStored() throws Exception {
        var sent = new ArrayList<OrderCommand>();
        try (var outbox = new MockOutbox(store.commands(), sent::add)) {
            jdbc.execute("DROP TABLE mock_command_log");
            assertThrows(IOException.class, () -> outbox.submit(command()));
            assertTrue(sent.isEmpty());
            assertTrue(outbox.entries().isEmpty());
        }
        try (var inbox = new MockInbox(store.results())) {
            jdbc.execute("DROP TABLE mock_result_log");
            assertThrows(IOException.class, () -> inbox.store(new OrderResult("one", 1, "BTC_USDT",
                    OrderResult.Status.OPEN, 1, 0, null, List.of())));
            assertTrue(inbox.results().isEmpty());
        }
    }
    @Test void cancellationAndTradeDetailsSurviveDatabaseReplay() throws Exception {
        var cancel = new OrderCommand("cancel-one", OrderCommand.Action.CANCEL, 1, "BTC_USDT", null, 0, 0);
        try (var outbox = new MockOutbox(store.commands(), command -> {})) { outbox.submit(cancel); }
        try (var outbox = new MockOutbox(store.commands(), command -> {})) {
            assertEquals(cancel, outbox.entries().getFirst().command());
        }
        var trade = new com.exchange.matching.protocol.model.Trade("trade-one", "BTC_USDT", 1, 2, 6500000, 1000);
        var result = new OrderResult("fill-one", 2, "BTC_USDT", OrderResult.Status.FILLED, 0, 0, null, List.of(trade));
        try (var inbox = new MockInbox(store.results())) { inbox.store(result); }
        try (var inbox = new MockInbox(store.results())) { assertEquals(result, inbox.results().getFirst()); }
    }
}
