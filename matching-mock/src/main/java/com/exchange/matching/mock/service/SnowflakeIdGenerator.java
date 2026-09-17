package com.exchange.matching.mock.service;

import java.time.Instant;
import java.util.function.LongSupplier;

/** 41 timestamp bits, 10 worker bits and 12 sequence bits; positive signed long. */
public final class SnowflakeIdGenerator {
    public static final long EPOCH = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli();
    private final int workerId;
    private final LongSupplier clock;
    private long lastMillis = -1;
    private int sequence;

    public SnowflakeIdGenerator(int workerId) { this(workerId, System::currentTimeMillis); }
    public SnowflakeIdGenerator(int workerId, LongSupplier clock) {
        if (workerId < 0 || workerId > 1023) throw new IllegalArgumentException("workerId must be 0–1023");
        this.workerId = workerId; this.clock = clock;
    }
    public synchronized long nextId() {
        long now = clock.getAsLong();
        long elapsed = now - EPOCH;
        if (elapsed < 0 || elapsed >= (1L << 41)) throw new IllegalStateException("雪花 ID 时间超出有效范围");
        if (now < lastMillis) throw new IllegalStateException("系统时钟回拨，请恢复时钟后重试");
        if (now == lastMillis && sequence == 4095) throw new IllegalStateException("当前毫秒 ID 已用尽，请稍后重试");
        sequence = now == lastMillis ? sequence + 1 : 0;
        lastMillis = now;
        long id = (elapsed << 22) | ((long) workerId << 12) | sequence;
        if (id == 0) { sequence = 1; return 1; }
        return id;
    }
    /** Restore only IDs explicitly marked as Snowflake, never interpret legacy random IDs. */
    public synchronized void restore(long id) {
        if (((id >>> 12) & 1023) != workerId) return;
        long millis = timestamp(id).toEpochMilli();
        int savedSequence = (int) (id & 4095);
        if (millis > lastMillis || (millis == lastMillis && savedSequence > sequence)) {
            lastMillis = millis; sequence = savedSequence;
        }
    }
    public static Instant timestamp(long id) {
        if (id <= 0) throw new IllegalArgumentException("ID must be positive");
        return Instant.ofEpochMilli(EPOCH + (id >>> 22));
    }
}
