package com.exchange.matching.server.repository;
import com.exchange.matching.server.dto.EngineConfiguration;
import com.exchange.matching.persistence.journal.DurableJournal;
import java.io.IOException;
import java.util.List;

public interface ConfigurationStore extends AutoCloseable {
    List<EngineConfiguration> readAll() throws IOException;
    void append(EngineConfiguration configuration) throws IOException;
    default void close() throws IOException {}
    static ConfigurationStore journal(DurableJournal<EngineConfiguration> journal) {
        return new ConfigurationStore() {
            public List<EngineConfiguration> readAll() throws IOException { return journal.readAll(); }
            public void append(EngineConfiguration value) throws IOException { journal.append(value); }
            public void close() throws IOException { journal.close(); }
        };
    }
}
