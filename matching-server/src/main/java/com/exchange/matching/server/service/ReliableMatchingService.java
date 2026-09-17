package com.exchange.matching.server.service;

import com.exchange.matching.core.orderbook.MatchingEngine;
import com.exchange.matching.persistence.journal.DurableJournal;
import com.exchange.matching.protocol.command.OrderCommand;
import com.exchange.matching.protocol.event.MatchingOutcome;
import com.exchange.matching.core.orderbook.EngineParameters;
import com.exchange.matching.server.dto.EngineConfiguration;
import com.exchange.matching.server.dto.EngineArchive;
import com.exchange.matching.persistence.snapshot.SnapshotStore;
import java.nio.file.Path;
import java.util.*;

import java.io.IOException;

public final class ReliableMatchingService implements AutoCloseable {
    public synchronized List<com.exchange.matching.protocol.event.MarketUpdate> marketSnapshots() {
        return engine.marketSnapshots();
    }
    private final MatchingEngine engine = new MatchingEngine();
    private final DurableJournal<OrderCommand> journal;
    private final com.exchange.matching.server.repository.ConfigurationStore configurations;
    private final EngineMetrics metrics;
    private EngineParameters parameters = EngineParameters.defaults();
    private long commandCount;

    public ReliableMatchingService(DurableJournal<OrderCommand> journal) throws IOException {
        this(journal, (DurableJournal<EngineConfiguration>) null);
    }

    public static ReliableMatchingService open(Path path) throws IOException {
        var commands = new DurableJournal<>(path, OrderCommand.class);
        DurableJournal<EngineConfiguration> settings;
        try { settings = new DurableJournal<>(Path.of(path + ".settings"), EngineConfiguration.class); }
        catch (IOException | RuntimeException failure) { commands.close(); throw failure; }
        return new ReliableMatchingService(commands, settings);
    }

    public ReliableMatchingService(DurableJournal<OrderCommand> journal,
                                   DurableJournal<EngineConfiguration> configurations) throws IOException {
        this(journal, configurations == null ? null : com.exchange.matching.server.repository.ConfigurationStore.journal(configurations), new EngineMetrics());
    }

    public static ReliableMatchingService open(Path path, com.exchange.matching.server.repository.AdminStore store, EngineMetrics metrics) throws IOException {
        var commands = new DurableJournal<>(path, OrderCommand.class);
        try {
            var legacyPath = Path.of(path + ".settings");
            if (store.readAll().isEmpty() && java.nio.file.Files.exists(legacyPath)) {
                try (var legacy = new DurableJournal<>(legacyPath, EngineConfiguration.class)) { store.migrate(legacy.readAll()); }
            }
            // The application owns the H2 connection, the service owns only its command journal.
            var configurations = new com.exchange.matching.server.repository.ConfigurationStore() {
                public List<EngineConfiguration> readAll() throws IOException { return store.readAll(); }
                public void append(EngineConfiguration value) throws IOException { store.append(value); }
            };
            return new ReliableMatchingService(commands, configurations, metrics);
        } catch (IOException | RuntimeException failure) { commands.close(); throw failure; }
    }

    private ReliableMatchingService(DurableJournal<OrderCommand> journal,
            com.exchange.matching.server.repository.ConfigurationStore configurations, EngineMetrics metrics) throws IOException {
        this.journal = journal;
        this.configurations = configurations;
        this.metrics = metrics;
        try {
            var commands = journal.readAll();
            var revisions = configurations == null ? List.<EngineConfiguration>of() : configurations.readAll();
            if (revisions.isEmpty() && configurations != null) {
                // Existing journals were unrestricted: preserve their historical symbols on first migration.
                var symbols = new HashSet<>(Set.of("BTC_USDT"));
                commands.forEach(command -> symbols.add(command.symbol()));
                parameters = new EngineParameters(symbols, 1, Long.MAX_VALUE);
                var initial = new EngineConfiguration(0, parameters);
                configurations.append(initial);
                revisions = List.of(initial);
            }
            if (!revisions.isEmpty() && revisions.getFirst().effectiveAfterCommands() != 0)
                throw new IOException("配置日志缺少初始配置");
            long previous = 0;
            for (var revision : revisions) {
                if (revision.effectiveAfterCommands() < previous || revision.effectiveAfterCommands() > commands.size()) {
                    throw new IOException("配置日志与命令日志不匹配");
                }
                previous = revision.effectiveAfterCommands();
            }
            int index = 0;
            for (int count = 0; count <= commands.size(); count++) {
                while (index < revisions.size() && revisions.get(index).effectiveAfterCommands() == count) {
                    parameters = revisions.get(index++).parameters();
                    engine.configure(parameters);
                }
                // Replay restores engine state and deduplication, never dashboard statistics.
                if (count < commands.size()) engine.process(commands.get(count));
            }
            commandCount = commands.size();
        } catch (IOException | RuntimeException failure) {
            journal.close();
            if (configurations != null) configurations.close();
            throw failure;
        }
    }

    public synchronized Map<String,Object> dashboard() {
        return metrics.snapshot(parameters.symbols(), engine.bookDepths());
    }

    public synchronized EngineConfiguration configuration() { return new EngineConfiguration(commandCount, parameters); }

    public synchronized long snapshot(Path path) throws IOException {
        var revisions = configurations == null
                ? List.of(new EngineConfiguration(0, parameters)) : configurations.readAll();
        new SnapshotStore<>(EngineArchive.class).write(path, new EngineArchive(journal.readAll(), revisions));
        return commandCount;
    }

    public synchronized EngineConfiguration configure(EngineParameters next) throws IOException {
        if (configurations == null) throw new IllegalStateException("未配置参数持久化存储");
        engine.validateConfiguration(next);
        configurations.append(new EngineConfiguration(commandCount, next));
        engine.configure(next);
        parameters = next;
        return configuration();
    }

    public synchronized void validate(OrderCommand command) { engine.validate(command); }

    public synchronized MatchingOutcome process(OrderCommand command) throws IOException {
        engine.validate(command);
        boolean fresh = !engine.hasProcessed(command.commandId());
        if (fresh) { journal.append(command); commandCount++; }
        var outcome = engine.process(command);
        if (fresh) metrics.processed(command.symbol(), outcome.result().trades());
        return outcome;
    }

    @Override
    public void close() throws IOException {
        try { journal.close(); } finally { if (configurations != null) configurations.close(); }
    }
}
