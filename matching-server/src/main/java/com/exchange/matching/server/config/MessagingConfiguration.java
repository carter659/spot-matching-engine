package com.exchange.matching.server.config;

import com.exchange.matching.persistence.journal.DurableJournal;
import com.exchange.matching.protocol.command.OrderCommand;
import com.exchange.matching.server.messaging.CommandHandler;
import com.exchange.matching.server.messaging.ConfirmedPublisher;
import com.exchange.matching.server.service.ReliableMatchingService;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.context.annotation.*;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.function.Consumer;

@Configuration
@Profile("messaging")
@org.springframework.scheduling.annotation.EnableScheduling
public class MessagingConfiguration {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MessagingConfiguration.class);

    @Bean
    MarketRefresh marketRefresh(ReliableMatchingService service, StreamBridge bridge,
                               ThreadPoolExecutor marketExecutor, com.exchange.matching.server.service.EngineMetrics metrics) {
        return new MarketRefresh(service, bridge, marketExecutor, metrics);
    }

    static class MarketRefresh {
        private final ReliableMatchingService service;
        private final StreamBridge bridge;
        private final ThreadPoolExecutor executor;
        private final com.exchange.matching.server.service.EngineMetrics metrics;
        private final java.util.concurrent.atomic.AtomicBoolean pending = new java.util.concurrent.atomic.AtomicBoolean();
        MarketRefresh(ReliableMatchingService service, StreamBridge bridge, ThreadPoolExecutor executor,
                      com.exchange.matching.server.service.EngineMetrics metrics) {
            this.service=service; this.bridge=bridge; this.executor=executor; this.metrics=metrics;
        }
        @org.springframework.scheduling.annotation.Scheduled(fixedDelayString="${matching.market-refresh-ms:5000}", initialDelay=5000)
        public void refresh() {
            if (!pending.compareAndSet(false, true)) return;
            try { executor.execute(() -> {
                try {
                    for (var update : service.marketSnapshots()) {
                        if (bridge.send("market-out-0", MessageBuilder.withPayload(update)
                                .setHeader("partitionKey", update.orderBook().symbol()).build())) metrics.event("kafkaPublished");
                        else metrics.event("kafkaFailed");
                    }
                } catch (RuntimeException failure) { metrics.event("kafkaFailed"); log.warn("Kafka snapshot refresh failed: {}", failure.toString()); }
                finally { pending.set(false); }
            }); } catch (RejectedExecutionException failure) { pending.set(false); metrics.event("kafkaDropped"); }
        }
    }

    @Bean(destroyMethod = "shutdown")
    ThreadPoolExecutor marketExecutor(com.exchange.matching.server.service.EngineMetrics metrics) {
        var executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1024), runnable -> {
                    Thread thread = new Thread(runnable, "market-publisher");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        metrics.backlog(() -> executor.getQueue().size());
        return executor;
    }

    @Bean
    Consumer<Message<OrderCommand>> commands(ReliableMatchingService service, StreamBridge bridge,
                                            ThreadPoolExecutor marketExecutor, com.exchange.matching.server.service.EngineMetrics metrics) {
        ConfirmedPublisher publisher = new ConfirmedPublisher(bridge);
        return new CommandHandler(service, result -> {
                    try { publisher.send("results-out-0", result); metrics.event("rabbitPublished"); }
                    catch (RuntimeException failure) { metrics.event("rabbitPublishFailed"); throw failure; }
                }, update -> {
                    try { marketExecutor.execute(() -> {
                    try {
                        boolean accepted = bridge.send("market-out-0", MessageBuilder.withPayload(update)
                                .setHeader("partitionKey", update.orderBook().symbol()).build());
                        if (accepted) metrics.event("kafkaPublished");
                        else { metrics.event("kafkaFailed"); log.warn("Market update rejected: {}", update.commandId()); }
                    } catch (RuntimeException failure) {
                        metrics.event("kafkaFailed");
                        log.warn("Kafka market update failed: {}", update.commandId(), failure);
                    }
                }); } catch (RejectedExecutionException failure) { metrics.event("kafkaDropped"); throw failure; }
                }, metrics);
    }
}
