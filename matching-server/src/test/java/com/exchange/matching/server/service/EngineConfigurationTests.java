package com.exchange.matching.server.service;

import com.exchange.matching.core.orderbook.EngineParameters;
import com.exchange.matching.protocol.command.OrderCommand;
import com.exchange.matching.protocol.event.OrderResult;
import com.exchange.matching.server.controller.EngineController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.file.Path;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class EngineConfigurationTests {
    @TempDir Path directory;
    OrderCommand order(String id, long orderId, OrderCommand.Side side) {
        return new OrderCommand(id, OrderCommand.Action.PLACE, orderId, "ETH_USDT", side, 100, 10);
    }
    @Test void replaysConfigurationAtOriginalCommandBoundaries() throws Exception {
        var path = directory.resolve("commands");
        var disabled = order("disabled", 1, OrderCommand.Side.BUY);
        var maker = order("maker", 2, OrderCommand.Side.SELL);
        try (var service = ReliableMatchingService.open(path)) {
            assertEquals(OrderResult.Status.REJECTED, service.process(disabled).result().status());
            service.configure(new EngineParameters(Set.of("ETH_USDT"), 1, 100));
            service.process(maker);
            service.configure(new EngineParameters(Set.of("BTC_USDT", "ETH_USDT"), 5, 50));
        }
        try (var restored = ReliableMatchingService.open(path)) {
            assertEquals(5, restored.configuration().parameters().minOrderLots());
            assertEquals(OrderResult.Status.REJECTED, restored.process(disabled).result().status());
            assertEquals(OrderResult.Status.OPEN, restored.process(maker).result().status());
            assertEquals(OrderResult.Status.FILLED, restored.process(order("taker", 3, OrderCommand.Side.BUY)).result().status());
        }
    }
    @Test void apiRejectsInvalidAndUnsafeChangesAndPersistsValidSelection() throws Exception {
        var path = directory.resolve("api");
        try (var service = ReliableMatchingService.open(path)) {
            var mvc = MockMvcBuilders.standaloneSetup(new EngineController(service)).build();
            mvc.perform(get("/api/engine/pairs")).andExpect(jsonPath("$.length()").value(20));
            mvc.perform(post("/api/engine/configuration").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"symbols\":[\"ETH_USDT\"],\"minOrderLots\":1,\"maxOrderLots\":100}"))
                    .andExpect(status().isOk());
            service.process(order("open", 1, OrderCommand.Side.BUY));
            mvc.perform(post("/api/engine/configuration").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"symbols\":[\"BTC_USDT\"],\"minOrderLots\":1,\"maxOrderLots\":100}"))
                    .andExpect(status().isBadRequest());
            mvc.perform(post("/api/engine/configuration").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"symbols\":[],\"minOrderLots\":1,\"maxOrderLots\":100}"))
                    .andExpect(status().isBadRequest());
        }
        try (var restored = ReliableMatchingService.open(path)) {
            assertEquals(Set.of("ETH_USDT"), restored.configuration().parameters().symbols());
        }
    }
    @Test void consecutiveConfigurationsWithoutCommandsRecoverLatest() throws Exception {
        var path = directory.resolve("empty");
        try (var service = ReliableMatchingService.open(path)) {
            service.configure(new EngineParameters(Set.of("ETH_USDT"), 1, 100));
            service.configure(new EngineParameters(Set.of("SOL_USDT"), 2, 200));
        }
        try (var restored = ReliableMatchingService.open(path)) {
            assertEquals(Set.of("SOL_USDT"), restored.configuration().parameters().symbols());
        }
    }
}
