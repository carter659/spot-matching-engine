package com.exchange.matching.persistence.snapshot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class SnapshotStoreTests {
    @TempDir Path directory;
    public record Value(String text, long sequence) {}
    @Test void roundTripAndReplace() throws Exception {
        var store = new SnapshotStore<>(Value.class);
        var path = directory.resolve("backup");
        store.write(path, new Value("初始", 1));
        store.write(path, new Value("替换", Long.MAX_VALUE));
        assertEquals(new Value("替换", Long.MAX_VALUE), store.read(path));
        try (var files = Files.list(directory)) { assertEquals(1, files.count()); }
    }
    @Test void rejectsCorruptionTruncationAndUnknownVersion() throws Exception {
        var store = new SnapshotStore<>(Value.class);
        var path = directory.resolve("backup");
        store.write(path, new Value("valid", 1));
        byte[] original = Files.readAllBytes(path);
        byte[] corrupt = original.clone(); corrupt[corrupt.length - 1] ^= 1;
        Files.write(path, corrupt); assertThrows(IOException.class, () -> store.read(path));
        Files.write(path, java.util.Arrays.copyOf(original, 20));
        assertThrows(IOException.class, () -> store.read(path));
        original[7] = 99; Files.write(path, original);
        assertThrows(IOException.class, () -> store.read(path));
    }
}
