package com.exchange.matching.persistence.snapshot;

import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Versioned replay backup. Publication requires atomic replacement on the local filesystem. */
public final class SnapshotStore<T> {
    private static final int MAGIC = 0x4d534e50, VERSION = 1, HEADER = 44;
    private static final int MAX_BYTES = 256 * 1024 * 1024;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final Class<T> type;

    public SnapshotStore(Class<T> type) { this.type = type; }

    public void write(Path path, T value) throws IOException {
        byte[] payload = mapper.writeValueAsBytes(value);
        if (payload.length > MAX_BYTES) throw new IOException("Snapshot exceeds size limit");
        Path target = path.toAbsolutePath();
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), "snapshot-", ".tmp");
        try {
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                var header = ByteBuffer.allocate(HEADER).putInt(MAGIC).putInt(VERSION)
                        .putInt(payload.length).put(digest(payload)).flip();
                while (header.hasRemaining()) channel.write(header);
                var data = ByteBuffer.wrap(payload);
                while (data.hasRemaining()) channel.write(data);
                channel.force(true);
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    public T read(Path path) throws IOException {
        long size = Files.size(path);
        if (size < HEADER || size > MAX_BYTES + (long) HEADER) throw new IOException("Invalid snapshot size");
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length < HEADER || bytes.length > MAX_BYTES + HEADER) throw new IOException("Invalid snapshot size");
        var data = ByteBuffer.wrap(bytes);
        if (data.getInt() != MAGIC || data.getInt() != VERSION) throw new IOException("Unknown snapshot format");
        int length = data.getInt();
        if (length != bytes.length - HEADER) throw new IOException("Invalid snapshot length");
        byte[] checksum = new byte[32], payload = new byte[length];
        data.get(checksum).get(payload);
        if (!MessageDigest.isEqual(checksum, digest(payload))) throw new IOException("Snapshot checksum mismatch");
        try { return mapper.readValue(payload, type); }
        catch (RuntimeException failure) { throw new IOException("Invalid snapshot payload", failure); }
    }

    private static byte[] digest(byte[] data) {
        try { return MessageDigest.getInstance("SHA-256").digest(data); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
