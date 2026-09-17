package com.exchange.matching.mock.repository;

import com.exchange.matching.persistence.journal.DurableJournal;
import com.exchange.matching.protocol.event.OrderResult;
import java.io.IOException;
import java.util.*;
public final class MockInbox implements AutoCloseable {
    private final RecordStore<OrderResult> journal;
    private final Map<String, OrderResult> results = new LinkedHashMap<>();
    public MockInbox(DurableJournal<OrderResult> journal) throws IOException {
        this(RecordStore.journal(journal));
    }
    public MockInbox(RecordStore<OrderResult> journal) throws IOException {
        this.journal = journal;
        try { for (OrderResult result : journal.readAll()) results.put(result.commandId(), result); }
        catch (IOException | RuntimeException failure) { journal.close(); throw failure; }
    }
    public synchronized void store(OrderResult result) throws IOException {
        OrderResult previous = results.get(result.commandId());
        if (previous != null) {
            if (!previous.equals(result)) throw new IllegalStateException("Conflicting duplicate result");
            return;
        }
        journal.append(result);
        results.put(result.commandId(), result);
    }
    public synchronized List<OrderResult> results() { return List.copyOf(results.values()); }
    @Override public void close() throws IOException { journal.close(); }
}
