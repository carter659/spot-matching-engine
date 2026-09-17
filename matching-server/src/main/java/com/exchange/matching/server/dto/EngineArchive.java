package com.exchange.matching.server.dto;

import com.exchange.matching.protocol.command.OrderCommand;
import java.util.List;

public record EngineArchive(List<OrderCommand> commands, List<EngineConfiguration> configurations) {
    public EngineArchive {
        commands = List.copyOf(commands);
        configurations = List.copyOf(configurations);
    }
}
