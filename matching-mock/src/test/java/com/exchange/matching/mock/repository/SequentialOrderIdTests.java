package com.exchange.matching.mock.repository;

import com.exchange.matching.protocol.command.OrderCommand;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class SequentialOrderIdTests {
    private OrderCommand command(String key, long id) {
        return new OrderCommand(key, OrderCommand.Action.PLACE, id, "BTC_USDT", OrderCommand.Side.BUY, 100, 1);
    }
    @Test void persistsSequenceAndRetryIdentityAcrossRestartAndConcurrentRequests() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var database = new MapperTestDatabase(source);
        var outbox = new MockOutbox(database.store().commands(), c -> {});
        long first = outbox.submitPlace("first", id -> command("first", id)).orderId();
        try (var executor = Executors.newFixedThreadPool(4)) {
            var tasks = new ArrayList<Callable<Long>>();
            for (int i = 0; i < 20; i++) {
                String key = "parallel-" + i;
                tasks.add(() -> outbox.submitPlace(key, id -> command(key, id)).orderId());
            }
            var ids = new TreeSet<Long>();
            for (var result : executor.invokeAll(tasks)) ids.add(result.get());
            assertEquals(20, ids.size()); assertTrue(ids.first() > first);
        }
        var restored = new MockOutbox(database.store().commands(), c -> {});
        assertEquals(first, restored.submitPlace("first", id -> command("first", id)).orderId());
        assertTrue(restored.submitPlace("next", id -> command("next", id)).orderId() > first);
        assertThrows(IllegalArgumentException.class, () -> restored.submitPlace("first", id ->
                new OrderCommand("first", OrderCommand.Action.PLACE, id, "BTC_USDT", OrderCommand.Side.SELL, 100, 1)));
        var failing = new MockOutbox(database.store().commands(), c -> { throw new IllegalStateException("broker offline"); });
        assertThrows(IllegalStateException.class, () -> failing.submitPlace("retry", id -> command("retry", id)));
        var recovered = new MockOutbox(database.store().commands(), c -> {});
        assertEquals(failing.pending().getFirst().orderId(), recovered.submitPlace("retry", id -> command("retry", id)).orderId());
        recovered.submit(command("legacy", 9000000000000000000L));
        assertTrue(recovered.submitPlace("after-legacy", id -> command("after-legacy", id)).orderId() < 9000000000000000000L);
        recovered.submit(command("maximum", Long.MAX_VALUE));
        assertTrue(recovered.submitPlace("overflow", id -> command("overflow", id)).orderId() > first);
        assertEquals(Long.MAX_VALUE, recovered.submitPlace("maximum", id -> command("maximum", id)).orderId());
    }
}
