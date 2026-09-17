package com.exchange.matching.mock.repository;

import com.exchange.matching.mock.service.SnowflakeIdGenerator;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SnowflakeIdGeneratorTests {
    @Test void readsLegacyOutboxRecordsWithoutSnowflakeMarker() {
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        var entry = json.readValue("{\"command\":null,\"confirmed\":true}", MockOutbox.Entry.class);
        assertFalse(entry.snowflake());
        assertTrue(entry.confirmed());
    }
    @Test void decodesTimestampAndHandlesRestartRollbackAndSequenceExhaustion() {
        var clock = new AtomicLong(SnowflakeIdGenerator.EPOCH + 1000);
        var generator = new SnowflakeIdGenerator(7, clock::get);
        long first = generator.nextId();
        assertEquals(clock.get(), SnowflakeIdGenerator.timestamp(first).toEpochMilli());
        assertEquals(7, (first >>> 12) & 1023);
        var restored = new SnowflakeIdGenerator(7, clock::get);
        restored.restore(first);
        assertEquals(first + 1, restored.nextId());
        clock.decrementAndGet();
        assertThrows(IllegalStateException.class, restored::nextId);
        clock.incrementAndGet();
        for (int i = 2; i <= 4095; i++) assertEquals(first + i, restored.nextId());
        assertThrows(IllegalStateException.class, restored::nextId);
        clock.incrementAndGet();
        assertTrue(restored.nextId() > first + 4095);
        assertNotEquals(restored.nextId(), new SnowflakeIdGenerator(8, clock::get).nextId());
        assertThrows(IllegalArgumentException.class, () -> new SnowflakeIdGenerator(1024));
    }
}
