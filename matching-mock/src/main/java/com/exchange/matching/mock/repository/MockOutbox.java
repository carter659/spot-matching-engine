package com.exchange.matching.mock.repository;

import com.exchange.matching.persistence.journal.DurableJournal;
import com.exchange.matching.protocol.command.OrderCommand;
import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.LongFunction;

public final class MockOutbox implements AutoCloseable {
    public record Entry(OrderCommand command, boolean confirmed, Boolean snowflake) {
        public Entry { snowflake = Boolean.TRUE.equals(snowflake); }
        public Entry(OrderCommand command, boolean confirmed) { this(command, confirmed, false); }
    }
    private final RecordStore<Entry> journal;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Consumer<OrderCommand> publisher;
    private final com.exchange.matching.mock.service.SnowflakeIdGenerator ids;
    private final Set<Long> usedIds = new HashSet<>();
    public MockOutbox(DurableJournal<Entry> journal, Consumer<OrderCommand> publisher) throws IOException {
        this(RecordStore.journal(journal), publisher);
    }
    public MockOutbox(RecordStore<Entry> journal, Consumer<OrderCommand> publisher) throws IOException {
        this(journal, publisher, new com.exchange.matching.mock.service.SnowflakeIdGenerator(0));
    }
    public MockOutbox(RecordStore<Entry> journal, Consumer<OrderCommand> publisher,
                      com.exchange.matching.mock.service.SnowflakeIdGenerator ids) throws IOException {
        this.ids = ids;
        this.journal = journal;
        this.publisher = publisher;
        try { for (Entry entry : journal.readAll()) {
            entries.put(entry.command().commandId(), entry);
            usedIds.add(entry.command().orderId());
            if (entry.snowflake()) ids.restore(entry.command().orderId());
        } }
        catch (IOException | RuntimeException failure) { journal.close(); throw failure; }
    }
    /** Allocation and durable submission share the outbox lock; retries retain their original identity. */
    public synchronized OrderCommand submitPlace(String commandId, LongFunction<OrderCommand> factory) throws IOException {
        Entry previous = entries.get(commandId);
        long id;
        if (previous != null) id = previous.command().orderId();
        else { do { id = ids.nextId(); } while (usedIds.contains(id)); }
        OrderCommand command = factory.apply(id);
        if (!Objects.equals(commandId, command.commandId()) || command.orderId() != id
                || command.action() != OrderCommand.Action.PLACE)
            throw new IllegalArgumentException("Invalid generated order identity");
        submit(command, previous == null || previous.snowflake());
        return command;
    }
    public synchronized void submit(OrderCommand command) throws IOException {
        submit(command, false);
    }
    private void submit(OrderCommand command, boolean snowflake) throws IOException {
        command.validate();
        Entry previous = entries.get(command.commandId());
        if (previous != null && !previous.command().equals(command)) {
            throw new IllegalArgumentException("commandId was reused with different content");
        }
        if (previous != null && previous.confirmed()) return;
        if (previous == null) {
            Entry pending = new Entry(command, false, snowflake);
            journal.append(pending);
            entries.put(command.commandId(), pending);
            usedIds.add(command.orderId());
        }
        retryPending();
    }
    public synchronized void retryPending() throws IOException {
        for (OrderCommand command : pending()) {
            publisher.accept(command);
            Entry confirmed = new Entry(command, true, entries.get(command.commandId()).snowflake());
            journal.append(confirmed);
            entries.put(command.commandId(), confirmed);
        }
    }
    public synchronized List<OrderCommand> pending() {
        return entries.values().stream().filter(entry -> !entry.confirmed()).map(Entry::command).toList();
    }
    public synchronized List<Entry> entries() { return List.copyOf(entries.values()); }
    @Override public void close() throws IOException { journal.close(); }
}
