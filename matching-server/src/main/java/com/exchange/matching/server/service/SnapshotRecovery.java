package com.exchange.matching.server.service;

import com.exchange.matching.persistence.journal.DurableJournal;
import com.exchange.matching.persistence.snapshot.SnapshotStore;
import com.exchange.matching.protocol.command.OrderCommand;
import com.exchange.matching.server.dto.EngineArchive;
import com.exchange.matching.server.dto.EngineConfiguration;
import java.io.IOException;
import java.nio.file.*;

public final class SnapshotRecovery {
    private SnapshotRecovery() {}

    /** Restore into a new directory only, validate replay, then publish without overwriting live data. */
    public static Path restore(Path snapshot, Path directory) throws IOException {
        var archive = new SnapshotStore<>(EngineArchive.class).read(snapshot);
        if (archive.configurations().isEmpty()) throw new IOException("Snapshot has no initial configuration");
        Path target = directory.toAbsolutePath().normalize();
        if (Files.exists(target)) throw new FileAlreadyExistsException(target.toString());
        Files.createDirectories(target.getParent());
        Path staging = Files.createTempDirectory(target.getParent(), "restore-");
        Path commands = staging.resolve("commands.journal");
        Path settings = staging.resolve("commands.journal.settings");
        try {
            try (var log = new DurableJournal<>(commands, OrderCommand.class);
                 var config = new DurableJournal<>(settings, EngineConfiguration.class)) {
                for (var command : archive.commands()) log.append(command);
                for (var revision : archive.configurations()) config.append(revision);
            }
            try (var service = ReliableMatchingService.open(commands)) { service.configuration(); }
            Files.move(staging, target);
            return target.resolve("commands.journal");
        } finally {
            Files.deleteIfExists(commands);
            Files.deleteIfExists(settings);
            Files.deleteIfExists(staging);
        }
    }
}
