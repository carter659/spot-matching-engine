package com.exchange.matching.server.config;

import com.exchange.matching.server.service.ReliableMatchingService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import java.io.IOException;
import java.nio.file.Path;

@Configuration
@Profile({"messaging", "configuration"})
public class EngineServiceConfiguration {
    @Bean(destroyMethod = "close")
    ReliableMatchingService matchingService(@Value("${matching.journal-path:data/engine/commands.journal}") String path,
            com.exchange.matching.server.repository.AdminStore store,
            com.exchange.matching.server.service.EngineMetrics metrics) throws IOException {
        return ReliableMatchingService.open(Path.of(path), store, metrics);
    }
}
