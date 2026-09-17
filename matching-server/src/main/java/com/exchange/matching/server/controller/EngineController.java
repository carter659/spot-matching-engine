package com.exchange.matching.server.controller;

import com.exchange.matching.core.orderbook.EngineParameters;
import com.exchange.matching.protocol.model.TradingPair;
import com.exchange.matching.server.dto.EngineConfiguration;
import com.exchange.matching.server.service.ReliableMatchingService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.util.*;

@RestController
@Profile({"messaging", "configuration"})
@RequestMapping("/api/engine")
public class EngineController {
    private final ReliableMatchingService service;
    @org.springframework.beans.factory.annotation.Value("${matching.journal-path:data/engine/commands.journal}")
    private String journalPath = "data/engine/commands.journal";
    public EngineController(ReliableMatchingService service) { this.service = service; }
    @GetMapping("/pairs") public List<TradingPair> pairs() { return TradingPair.CATALOG; }
    @GetMapping("/configuration") public EngineConfiguration configuration() { return service.configuration(); }
    @PostMapping("/snapshot") public Map<String, Object> snapshot() throws IOException {
        var path = java.nio.file.Path.of(journalPath + ".snapshot");
        return Map.of("commandCount", service.snapshot(path), "path", path.toAbsolutePath().toString());
    }
    @PostMapping("/configuration") public EngineConfiguration configure(@RequestBody EngineParameters parameters) throws IOException {
        return service.configure(parameters);
    }
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> invalid(IllegalArgumentException error) { return Map.of("error", error.getMessage()); }
    @ExceptionHandler(IOException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Map<String, String> storage(IOException error) { return Map.of("error", "持久化操作失败，请检查日志和磁盘后重试"); }
}
