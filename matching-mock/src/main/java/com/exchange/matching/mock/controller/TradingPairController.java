package com.exchange.matching.mock.controller;

import com.exchange.matching.mock.dto.TradingPairUpdate;
import com.exchange.matching.mock.repository.TradingPairRepository;
import com.exchange.matching.protocol.model.TradingPair;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@Profile("database")
@RequestMapping("/mock/parameters/pairs")
public class TradingPairController {
    private final TradingPairRepository repository;
    public TradingPairController(TradingPairRepository repository) { this.repository = repository; }
    @GetMapping public List<TradingPair> read() { return repository.read(); }
    @PostMapping public Map<String, String> add(@RequestBody TradingPair pair) {
        repository.add(pair); return Map.of("status", "created");
    }
    @DeleteMapping("/{symbol}") public Map<String, String> delete(@PathVariable String symbol) {
        repository.delete(symbol); return Map.of("status", "deleted");
    }
    @PostMapping("/{symbol}") public Map<String, String> update(@PathVariable String symbol, @RequestBody TradingPairUpdate body) {
        repository.update(symbol, body.examplePrice(), body.priceScale(), body.quantityScale());
        return Map.of("status", "saved");
    }
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> invalid(IllegalArgumentException error) { return Map.of("error", error.getMessage()); }
    @ExceptionHandler({DataAccessException.class, IllegalStateException.class})
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Map<String, String> unavailable() { return Map.of("error", "交易对配置不可用，请检查数据库及协议精度"); }
}
