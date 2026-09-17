package com.exchange.matching.mock.controller;

import com.exchange.matching.mock.repository.MockInbox;
import com.exchange.matching.mock.repository.MockOutbox;
import com.exchange.matching.protocol.command.OrderCommand;
import com.exchange.matching.protocol.event.MarketUpdate;
import com.exchange.matching.protocol.event.OrderResult;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.util.*;

@RestController
@Profile("messaging")
@RequestMapping("/mock")
public class MockController {
    private final MockOutbox outbox;
    private final MockInbox inbox;
    private final Map<String, MarketUpdate> market;
    public MockController(MockOutbox outbox, MockInbox inbox, Map<String, MarketUpdate> latestMarket) {
        this.outbox = outbox;
        this.inbox = inbox;
        this.market = latestMarket;
    }
    @PostMapping("/commands")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, String> command(@RequestBody OrderCommand command) throws IOException {
        outbox.submit(command);
        return Map.of("commandId", command.commandId(), "status", "BROKER_CONFIRMED");
    }
    @PostMapping("/retry")
    public List<OrderCommand> retry() throws IOException { outbox.retryPending(); return outbox.pending(); }
    @GetMapping("/pending")
    public List<OrderCommand> pending() { return outbox.pending(); }
    @GetMapping("/results")
    public List<OrderResult> results() { return inbox.results(); }
    @GetMapping("/market")
    public Map<String, MarketUpdate> market() { return Map.copyOf(market); }
    @PostMapping("/scenarios/basic")
    public List<OrderCommand> basicScenario() throws IOException {
        String batch = UUID.randomUUID().toString();
        var sell = outbox.submitPlace(batch + "-sell", id ->
                new OrderCommand(batch + "-sell", OrderCommand.Action.PLACE, id, "BTC_USDT",
                        OrderCommand.Side.SELL, 10000, 10));
        var buy = outbox.submitPlace(batch + "-buy", id ->
                new OrderCommand(batch + "-buy", OrderCommand.Action.PLACE, id, "BTC_USDT",
                        OrderCommand.Side.BUY, 10000, 4));
        var cancel = new OrderCommand(batch + "-cancel", OrderCommand.Action.CANCEL, sell.orderId(), "BTC_USDT", null, 0, 0);
        outbox.submit(cancel);
        return List.of(sell, buy, cancel);
    }
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> invalid(IllegalArgumentException failure) {
        return Map.of("error", failure.getMessage());
    }
}
