package com.exchange.matching.server.messaging;

import com.exchange.matching.protocol.command.OrderCommand;
import com.exchange.matching.protocol.event.MarketUpdate;
import com.exchange.matching.protocol.event.MatchingOutcome;
import com.exchange.matching.protocol.event.OrderResult;
import com.exchange.matching.server.service.ReliableMatchingService;

import com.rabbitmq.client.Channel;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.Message;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.function.Consumer;

public final class CommandHandler implements Consumer<Message<OrderCommand>> {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CommandHandler.class);
    private final ReliableMatchingService service;
    private final Consumer<OrderResult> results;
    private final Consumer<MarketUpdate> market;
    private final com.exchange.matching.server.service.EngineMetrics metrics;

    public CommandHandler(ReliableMatchingService service, Consumer<OrderResult> results, Consumer<MarketUpdate> market) {
        this(service, results, market, new com.exchange.matching.server.service.EngineMetrics());
    }
    public CommandHandler(ReliableMatchingService service, Consumer<OrderResult> results, Consumer<MarketUpdate> market,
                          com.exchange.matching.server.service.EngineMetrics metrics) {
        this.metrics = metrics;
        this.service = service;
        this.results = results;
        this.market = market;
    }

    @Override
    public void accept(Message<OrderCommand> message) {
        Channel channel = Objects.requireNonNull(message.getHeaders().get(AmqpHeaders.CHANNEL, Channel.class));
        long tag = Objects.requireNonNull(message.getHeaders().get(AmqpHeaders.DELIVERY_TAG, Long.class));
        metrics.event("rabbitReceived");
        try {
            try {
                service.validate(message.getPayload());
            } catch (IllegalArgumentException failure) {
                log.error("Invalid command sent to DLQ: {}", message.getPayload().commandId(), failure);
                channel.basicReject(tag, false);
                metrics.event("rabbitRejected");
                return;
            }
            MatchingOutcome outcome;
            try {
                long matchStarted = System.nanoTime();
                outcome = service.process(message.getPayload());
                OrderCommand command = message.getPayload();
                log.info(
                    "BOOK_LAG stage=engine.matched orderId={} commandId={} matchMs={}",
                    command.orderId(),
                    command.commandId(),
                    (System.nanoTime() - matchStarted) / 1_000_000);
                results.accept(outcome.result());
            } catch (Exception failure) {
                log.error("Reliable processing failed; command will be redelivered", failure);
                channel.basicNack(tag, false, true);
                metrics.event("rabbitRetried");
                return;
            }
            channel.basicAck(tag, false);
            metrics.event("rabbitAcked");
            try { market.accept(outcome.market()); }
            catch (RuntimeException failure) { log.warn("Market update dropped after reliable result confirmation", failure); }
        } catch (IOException failure) {
            throw new UncheckedIOException("RabbitMQ acknowledgment failed", failure);
        }
    }
}
