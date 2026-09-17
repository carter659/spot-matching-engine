package com.exchange.matching.mock.messaging;

import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.messaging.support.MessageBuilder;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Broker confirmation is required; StreamBridge.send(true) alone does not confirm delivery. */
public final class ConfirmedPublisher {
    private final StreamBridge bridge;
    public ConfirmedPublisher(StreamBridge bridge) { this.bridge = bridge; }

    public void send(String binding, Object payload) {
        CorrelationData correlation = new CorrelationData(UUID.randomUUID().toString());
        var message = MessageBuilder.withPayload(payload)
                .setHeader(AmqpHeaders.PUBLISH_CONFIRM_CORRELATION, correlation).build();
        if (!bridge.send(binding, message)) throw new IllegalStateException("Send rejected: " + binding);
        try {
            var confirm = correlation.getFuture().get(10, TimeUnit.SECONDS);
            if (!confirm.ack() || correlation.getReturned() != null) {
                throw new IllegalStateException("RabbitMQ did not route and confirm message: " + binding);
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting for RabbitMQ confirmation", failure);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure) {
            throw new IllegalStateException("RabbitMQ confirmation failed; retry with the same commandId", failure);
        }
    }
}
