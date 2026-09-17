package com.exchange.matching.server.controller;
import com.exchange.matching.server.service.*;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@Profile({"messaging","configuration"})
public class MetricsController {
    private final ReliableMatchingService service;
    private final RabbitQueueMonitor queues;
    public MetricsController(ReliableMatchingService service, RabbitQueueMonitor queues) { this.service=service;this.queues=queues; }
    @GetMapping("/api/engine/metrics") public Map<String,Object> metrics() {
        var snapshot=new java.util.LinkedHashMap<String,Object>(service.dashboard());
        snapshot.put("rabbitCommandQueue",queues.snapshot());
        return snapshot;
    }
}
