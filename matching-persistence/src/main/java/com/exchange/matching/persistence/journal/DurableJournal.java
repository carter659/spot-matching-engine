package com.exchange.matching.persistence.journal;

import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.util.*;
import java.util.zip.CRC32;

/** Local append-only, checksummed journal. One process per file; incomplete last frame is discarded. */
public final class DurableJournal<T> implements AutoCloseable {
    private static final int MAX_RECORD_BYTES = 16 * 1024 * 1024;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final Class<T> type;
    private final FileChannel channel;
    private final FileLock lock;
    private boolean failed;

    public DurableJournal(Path path, Class<T> type) throws IOException {
        this.type = type;
        Path absolute = path.toAbsolutePath();
        Files.createDirectories(absolute.getParent());
        channel = FileChannel.open(absolute, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        try {
            lock = channel.tryLock();
            if (lock == null) throw new IOException("Journal is already in use: " + absolute);
        } catch (IOException | RuntimeException failure) {
            channel.close();
            throw failure;
        }
    }

    public synchronized List<T> readAll() throws IOException {
        List<T> records = new ArrayList<>();
        channel.position(0);
        while (channel.position() < channel.size()) {
            long start = channel.position();
            if (channel.size() - start < 4) { truncateTail(start); break; }
            ByteBuffer header = ByteBuffer.allocate(4);
            readFully(header);
            int length = header.flip().getInt();
            if (length < 0 || length > MAX_RECORD_BYTES) throw new IOException("Invalid journal frame at " + start);
            if (channel.size() - channel.position() < length + 4L) { truncateTail(start); break; }
            ByteBuffer data = ByteBuffer.allocate(length);
            readFully(data);
            ByteBuffer checksum = ByteBuffer.allocate(4);
            readFully(checksum);
            CRC32 crc = new CRC32();
            crc.update(data.array());
            if ((int) crc.getValue() != checksum.flip().getInt()) throw new IOException("Journal checksum mismatch at " + start);
            records.add(mapper.readValue(data.array(), type));
        }
        return records;
    }

    public synchronized void append(T record) throws IOException {
        if (failed) throw new IOException("Journal failed; restart and recover before continuing");
        byte[] data = mapper.writeValueAsBytes(record);
        if (data.length > MAX_RECORD_BYTES) throw new IOException("Journal record exceeds size limit");
        CRC32 crc = new CRC32();
        crc.update(data);
        ByteBuffer frame = ByteBuffer.allocate(data.length + 8);
        frame.putInt(data.length).put(data).putInt((int) crc.getValue()).flip();
        long start = channel.size();
        try {
            channel.position(start);
            while (frame.hasRemaining()) channel.write(frame);
            channel.force(true);
        } catch (IOException failure) {
            failed = true;
            try { truncateTail(start); } catch (IOException rollback) { failure.addSuppressed(rollback); }
            throw failure;
        }
    }

    private void readFully(ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) throw new IOException("Unexpected journal EOF");
        }
    }

    private void truncateTail(long position) throws IOException {
        channel.truncate(position);
        channel.position(position);
        channel.force(true);
    }

    @Override
    public synchronized void close() throws IOException {
        try { lock.release(); } finally { channel.close(); }
    }
}
