package com.exchange.matching.mock.config;

import com.exchange.matching.mock.controller.MockController;
import com.exchange.matching.mock.controller.TradingController;
import com.exchange.matching.mock.service.TradingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.stream.function.StreamBridge;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class MockWiringTests {
    @TempDir Path directory;
    @Test void wiresMockControllerConsumersAndDurableStores() {
        new ApplicationContextRunner().withPropertyValues("spring.profiles.active=messaging",
                        "mock.data-directory=" + directory)
                .withUserConfiguration(MockMessagingConfiguration.class, MockController.class,
                        com.exchange.matching.mock.service.TradingService.class, com.exchange.matching.mock.controller.TradingController.class)
                .withBean(StreamBridge.class, () -> mock(StreamBridge.class))
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertNotNull(context.getBean(MockController.class));
                    assertNotNull(context.getBean(com.exchange.matching.mock.controller.TradingController.class));
                    assertNotNull(context.getBean("results"));
                    assertNotNull(context.getBean("market"));
                });
    }
}
