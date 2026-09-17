package com.exchange.matching.mock.config;

import com.exchange.matching.mock.market.MarketTradeHistory;
import com.exchange.matching.mock.messaging.ConfirmedPublisher;
import com.exchange.matching.mock.repository.MockInbox;
import com.exchange.matching.mock.repository.MockOutbox;
import com.exchange.matching.mock.repository.MysqlMockStore;
import org.springframework.beans.factory.ObjectProvider;
import com.exchange.matching.persistence.journal.DurableJournal;
import com.exchange.matching.protocol.event.MarketUpdate;
import com.exchange.matching.protocol.event.OrderResult;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.context.annotation.*;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.Message;
import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@Configuration
@Profile("messaging")
public class MockMessagingConfiguration {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MockMessagingConfiguration.class);
    @Bean(destroyMethod = "close")
    MockInbox mockInbox(@Value("${mock.data-directory:data/mock}") String directory,
                       ObjectProvider<MysqlMockStore> database) throws IOException {
        var store = database.getIfAvailable();
        if (store != null) return new MockInbox(store.results());
        return new MockInbox(new DurableJournal<>(Path.of(directory, "results.journal"), OrderResult.class));
    }
    @Bean(destroyMethod = "close")
    MockOutbox mockOutbox(StreamBridge bridge, @Value("${mock.data-directory:data/mock}") String directory,
                         ObjectProvider<MysqlMockStore> database, @Value("${mock.snowflake.worker-id:0}") int workerId) throws IOException {
        var publisher = new ConfirmedPublisher(bridge);
        var store = database.getIfAvailable();
        var ids = new com.exchange.matching.mock.service.SnowflakeIdGenerator(workerId);
        if (store != null) return new MockOutbox(store.commands(), command -> publisher.send("commands-out-0", command), ids);
        return new MockOutbox(com.exchange.matching.mock.repository.RecordStore.journal(new DurableJournal<>(Path.of(directory, "outbox.journal"), MockOutbox.Entry.class)),
                command -> publisher.send("commands-out-0", command), ids);
    }
    @Bean PendingRetry pendingRetry(MockOutbox outbox) { return new PendingRetry(outbox); }
    public static final class PendingRetry {
        private final MockOutbox outbox;
        PendingRetry(MockOutbox outbox) { this.outbox = outbox; }
        @EventListener(ApplicationReadyEvent.class)
        public void replayPending() {
            try { outbox.retryPending(); }
            catch (Exception failure) { log.error("Pending commands retained; retry via POST /mock/retry", failure); }
        }
    }
    @Bean Map<String, MarketUpdate> latestMarket() { return new ConcurrentHashMap<>(); }
    @Bean MarketTradeHistory marketTradeHistory() { return new MarketTradeHistory(); }
    @Bean Consumer<Message<OrderResult>> results(MockInbox inbox) {
        return message -> {
            Channel channel = Objects.requireNonNull(message.getHeaders().get(AmqpHeaders.CHANNEL, Channel.class));
            long tag = Objects.requireNonNull(message.getHeaders().get(AmqpHeaders.DELIVERY_TAG, Long.class));
            try {
                try { inbox.store(message.getPayload()); }
                catch (Exception failure) {
                    log.error("Result persistence failed; result will be redelivered", failure);
                    channel.basicNack(tag, false, true);
                    return;
                }
                channel.basicAck(tag, false);
            } catch (IOException failure) { throw new UncheckedIOException(failure); }
        };
    }
    @Bean Consumer<MarketUpdate> market(Map<String, MarketUpdate> latestMarket, MarketTradeHistory history) {
        return update -> latestMarket.compute(update.orderBook().symbol(), (symbol, previous) -> {
            if (previous != null && update.orderBook().sequence() <= previous.orderBook().sequence()) return previous;
            history.record(update.latestTrades());
            return update;
        });
    }
}
