package com.exchange.matching.mock.controller;

import com.exchange.matching.mock.dto.BookStrategyRequest;
import com.exchange.matching.mock.entity.BookStrategyEntity;
import com.exchange.matching.mock.service.BookStrategyService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @Profile("database & messaging") @RequestMapping("/mock/strategies")
public class BookStrategyController {
    private final BookStrategyService service;
    public BookStrategyController(BookStrategyService service) { this.service = service; }
    @GetMapping public List<BookStrategyEntity> list() { return service.list(); }
    @PutMapping("/{id}") public Map<String,String> update(@PathVariable String id, @RequestBody BookStrategyRequest body) { service.update(id,body); return Map.of("status","updated"); }
    @GetMapping("/{id}/snapshots") public List<com.exchange.matching.mock.entity.StrategySnapshotEntity> snapshots(@PathVariable String id) { return service.snapshots(id); }
    @GetMapping("/snapshots/{id}/orders") public List<com.exchange.matching.mock.entity.StrategyOrderEntity> orders(@PathVariable String id) { return service.snapshotOrders(id); }
    @PostMapping public Map<String,String> add(@RequestBody BookStrategyRequest body) { service.add(body); return Map.of("status","added"); }
    @PostMapping("/{id}/start") public Map<String,String> start(@PathVariable String id) { service.start(id); return Map.of("status","starting"); }
    @PostMapping("/{id}/pause") public Map<String,String> pause(@PathVariable String id) { service.pause(id,false); return Map.of("status","cancelling"); }
    @DeleteMapping("/{id}") public Map<String,String> delete(@PathVariable String id) { service.pause(id,true); return Map.of("status","deleting"); }
    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String,String> invalid(IllegalArgumentException error) { return Map.of("error",error.getMessage()); }
    @ExceptionHandler({org.springframework.dao.DataAccessException.class,IllegalStateException.class}) @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Map<String,String> unavailable() { return Map.of("error","策略服务暂不可用，请重新读取确认状态"); }
}
