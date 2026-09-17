package com.exchange.matching.persistence.journal;

import com.exchange.matching.protocol.command.OrderCommand;
import com.exchange.matching.protocol.model.OrderType;
import com.exchange.matching.protocol.model.TimeInForce;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class DurableJournalTests {
    @Test void oldJsonCommandsDefaultToLimitGtc() {
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        var command = mapper.readValue("""
                {"commandId":"legacy","action":"PLACE","orderId":1,"symbol":"BTC_USDT",
                 "side":"BUY","priceTicks":100,"quantityLots":2}
                """, OrderCommand.class);
        assertEquals(com.exchange.matching.protocol.model.OrderType.LIMIT, command.orderType());
        assertEquals(com.exchange.matching.protocol.model.TimeInForce.GTC, command.timeInForce());
    }
    @TempDir Path directory;
    OrderCommand command() { return new OrderCommand("one", OrderCommand.Action.PLACE, 1, "BTC_USDT", OrderCommand.Side.BUY, 100, 2); }
    @Test void replaysAndDiscardsOnlyIncompleteTail() throws Exception {
        Path path = directory.resolve("test.journal");
        try (var journal = new DurableJournal<>(path, OrderCommand.class)) { journal.append(command()); }
        long committedSize = Files.size(path);
        Files.write(path, new byte[]{0, 0}, StandardOpenOption.APPEND);
        try (var journal = new DurableJournal<>(path, OrderCommand.class)) {
            assertEquals(java.util.List.of(command()), journal.readAll());
            assertEquals(committedSize, Files.size(path));
        }
    }
    @Test void rejectsCorruptCompleteFrame() throws Exception {
        Path path = directory.resolve("corrupt.journal");
        try (var journal = new DurableJournal<>(path, OrderCommand.class)) { journal.append(command()); }
        byte[] bytes = Files.readAllBytes(path);
        bytes[8] ^= 1;
        Files.write(path, bytes);
        try (var journal = new DurableJournal<>(path, OrderCommand.class)) {
            assertThrows(IOException.class, journal::readAll);
        }
    }
    @Test void refusesConcurrentWriter() throws Exception {
        Path path = directory.resolve("locked.journal");
        try (var ignored = new DurableJournal<>(path, OrderCommand.class)) {
            assertThrows(java.nio.channels.OverlappingFileLockException.class,
                    () -> new DurableJournal<>(path, OrderCommand.class));
        }
    }
}
