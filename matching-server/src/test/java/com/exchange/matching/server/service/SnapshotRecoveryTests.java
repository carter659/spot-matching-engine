package com.exchange.matching.server.service;

import com.exchange.matching.core.orderbook.EngineParameters;
import com.exchange.matching.persistence.journal.DurableJournal;
import com.exchange.matching.protocol.command.OrderCommand;
import com.exchange.matching.protocol.event.OrderResult;
import com.exchange.matching.server.dto.EngineConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.IOException;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class SnapshotRecoveryTests {
    @TempDir Path directory;
    private OrderCommand order(String id, long number, OrderCommand.Side side) {
        return new OrderCommand(id, OrderCommand.Action.PLACE, number, "ETH_USDT", side, 100, 10);
    }
    @Test void restoresOpenOrdersConfigurationAndHistoricalRejections() throws Exception {
        Path backup = directory.resolve("backup"), target = directory.resolve("restored");
        var rejected = order("rejected", 1, OrderCommand.Side.BUY);
        var maker = order("maker", 2, OrderCommand.Side.SELL);
        try (var service = ReliableMatchingService.open(directory.resolve("original"))) {
            assertEquals(OrderResult.Status.REJECTED, service.process(rejected).result().status());
            service.configure(new EngineParameters(Set.of("ETH_USDT"), 1, 100));
            service.process(maker);
            assertEquals(2, service.snapshot(backup));
        }
        Path restored = SnapshotRecovery.restore(backup, target);
        try (var service = ReliableMatchingService.open(restored)) {
            assertEquals(Set.of("ETH_USDT"), service.configuration().parameters().symbols());
            assertEquals(OrderResult.Status.REJECTED, service.process(rejected).result().status());
            assertEquals(OrderResult.Status.OPEN, service.process(maker).result().status());
            assertEquals(2, service.configuration().effectiveAfterCommands());
            assertEquals(OrderResult.Status.FILLED, service.process(order("taker", 3, OrderCommand.Side.BUY)).result().status());
        }
        byte[] before = Files.readAllBytes(restored);
        assertThrows(FileAlreadyExistsException.class, () -> SnapshotRecovery.restore(backup, target));
        assertArrayEquals(before, Files.readAllBytes(restored));
    }
    @Test void rejectsMissingInitialConfigurationAndReleasesJournalLocks() throws Exception {
        Path path = directory.resolve("invalid");
        try (var settings = new DurableJournal<>(Path.of(path + ".settings"), EngineConfiguration.class)) {
            settings.append(new EngineConfiguration(-1, EngineParameters.defaults()));
        }
        assertThrows(IOException.class, () -> ReliableMatchingService.open(path));
        try (var reopened = new DurableJournal<>(path, OrderCommand.class)) { assertTrue(reopened.readAll().isEmpty()); }
    }
}
