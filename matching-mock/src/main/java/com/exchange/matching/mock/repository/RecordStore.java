package com.exchange.matching.mock.repository;

import com.exchange.matching.persistence.journal.DurableJournal;
import java.io.IOException;
import java.util.List;

/** Durable records, replayed in append order when mock starts. */
public interface RecordStore<T> extends AutoCloseable {
    List<T> readAll() throws IOException;
    void append(T record) throws IOException;
    @Override default void close() throws IOException {}

    static <T> RecordStore<T> journal(DurableJournal<T> journal) {
        return new RecordStore<>() {
            public List<T> readAll() throws IOException { return journal.readAll(); }
            public void append(T record) throws IOException { journal.append(record); }
            public void close() throws IOException { journal.close(); }
        };
    }
}
