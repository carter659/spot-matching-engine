package com.exchange.matching.server.messaging;

import com.exchange.matching.persistence.journal.DurableJournal;
import com.exchange.matching.protocol.command.OrderCommand;
import com.exchange.matching.protocol.event.MatchingOutcome;
import com.exchange.matching.protocol.event.OrderResult;
import com.exchange.matching.server.service.ReliableMatchingService;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.support.MessageBuilder;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CommandHandlerTests {
    @TempDir Path directory;
    OrderCommand command() { return new OrderCommand("one", OrderCommand.Action.PLACE, 1, "BTC_USDT", OrderCommand.Side.BUY, 100, 2); }
    ReliableMatchingService service() throws Exception {
        return new ReliableMatchingService(new DurableJournal<>(directory.resolve("commands"), OrderCommand.class));
    }
    org.springframework.messaging.Message<OrderCommand> message(Channel channel, OrderCommand command) {
        return MessageBuilder.withPayload(command).setHeader(AmqpHeaders.CHANNEL, channel)
                .setHeader(AmqpHeaders.DELIVERY_TAG, 7L).build();
    }
    @Test void confirmsResultBeforeAckAndMarketAfterAck() throws Exception {
        Channel channel = mock(Channel.class);
        List<String> order = new ArrayList<>();
        doAnswer(ignored -> { order.add("ack"); return null; }).when(channel).basicAck(7, false);
        try (var service = service()) {
            new CommandHandler(service, result -> order.add("confirmed"), update -> order.add("market"))
                    .accept(message(channel, command()));
        }
        assertEquals(List.of("confirmed", "ack", "market"), order);
    }
    @Test void failedPublishNacksAndRedeliveryDoesNotRepeatMatching() throws Exception {
        Channel channel = mock(Channel.class);
        try (var service = service()) {
            new CommandHandler(service, result -> { throw new IllegalStateException("broker unavailable"); }, update -> fail())
                    .accept(message(channel, command()));
            verify(channel).basicNack(7, false, true);
            verify(channel, never()).basicAck(anyLong(), anyBoolean());
            List<OrderResult> results = new ArrayList<>();
            new CommandHandler(service, results::add, update -> {}).accept(message(channel, command()));
            assertEquals(2, results.getFirst().remainingLots());
            assertEquals(2, service.process(command()).market().orderBook().bids().getFirst().totalRemainingLots());
        }
    }
    @Test void replayRestoresDeduplicationAndBook() throws Exception {
        MatchingOutcome expected;
        try (var service = service()) { expected = service.process(command()); }
        try (var service = service()) {
            assertEquals(expected, service.process(command()));
            var cancel = service.process(new OrderCommand("cancel", OrderCommand.Action.CANCEL, 1, "BTC_USDT", null, 0, 0));
            assertEquals(2, cancel.result().cancelledLots());
            assertTrue(cancel.market().orderBook().bids().isEmpty());
        }
    }
    @Test void invalidCommandIsRejectedToDlq() throws Exception {
        Channel channel = mock(Channel.class);
        try (var service = service()) {
            new CommandHandler(service, result -> fail(), update -> fail()).accept(message(channel,
                    new OrderCommand("invalid", OrderCommand.Action.PLACE, 1, "BTC_USDT", null, 0, 0)));
        }
        verify(channel).basicReject(7, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }
    @Test void kafkaFailureDoesNotUndoReliableAck() throws Exception {
        Channel channel = mock(Channel.class);
        try (var service = service()) {
            new CommandHandler(service, result -> {}, update -> { throw new IllegalStateException("Kafka down"); })
                    .accept(message(channel, command()));
        }
        verify(channel).basicAck(7, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }
}
