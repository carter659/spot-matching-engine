package com.exchange.matching.server.messaging;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.messaging.Message;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConfirmedPublisherTests {
    @Test void waitsForPositiveBrokerConfirmation() {
        StreamBridge bridge = mock(StreamBridge.class);
        when(bridge.send(eq("out"), any())).thenAnswer(call -> {
            Message<?> message = call.getArgument(1);
            var correlation = message.getHeaders().get(AmqpHeaders.PUBLISH_CONFIRM_CORRELATION, CorrelationData.class);
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return true;
        });
        assertDoesNotThrow(() -> new ConfirmedPublisher(bridge).send("out", "payload"));
    }
    @Test void refusesNegativeBrokerConfirmation() {
        StreamBridge bridge = mock(StreamBridge.class);
        when(bridge.send(eq("out"), any())).thenAnswer(call -> {
            Message<?> message = call.getArgument(1);
            var correlation = message.getHeaders().get(AmqpHeaders.PUBLISH_CONFIRM_CORRELATION, CorrelationData.class);
            correlation.getFuture().complete(new CorrelationData.Confirm(false, "rejected"));
            return true;
        });
        assertThrows(IllegalStateException.class, () -> new ConfirmedPublisher(bridge).send("out", "payload"));
    }
    @Test void refusesReturnedMessageEvenWithPositiveConfirmation() {
        StreamBridge bridge = mock(StreamBridge.class);
        when(bridge.send(eq("out"), any())).thenAnswer(call -> {
            Message<?> message = call.getArgument(1);
            var correlation = message.getHeaders().get(AmqpHeaders.PUBLISH_CONFIRM_CORRELATION, CorrelationData.class);
            correlation.setReturned(new org.springframework.amqp.core.ReturnedMessage(
                    new org.springframework.amqp.core.Message(new byte[0]), 312, "NO_ROUTE", "exchange", "key"));
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return true;
        });
        assertThrows(IllegalStateException.class, () -> new ConfirmedPublisher(bridge).send("out", "payload"));
    }
}
