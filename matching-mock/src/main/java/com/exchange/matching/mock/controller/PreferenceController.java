package com.exchange.matching.mock.controller;

import com.exchange.matching.mock.dto.PreferenceRequest;
import com.exchange.matching.mock.repository.PreferenceRepository;
import com.exchange.matching.protocol.model.TradingPair;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.Set;

@RestController
@Profile("database")
@RequestMapping("/mock/preferences")
public class PreferenceController {
    private final PreferenceRepository repository;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.exchange.matching.mock.repository.TradingPairRepository pairs;
    public PreferenceController(PreferenceRepository repository) { this.repository = repository; }
    @GetMapping public Map<String, String> read() { return repository.read(); }
    @PostMapping public Map<String, String> save(@RequestBody PreferenceRequest request) {
        String key = request.key(), value = request.value();
        boolean valid = key != null && value != null && switch (key) {
            case "theme" -> Set.of("light", "dark").contains(value);
            case "symbol" -> pairs == null ? TradingPair.CATALOG.stream().anyMatch(pair -> pair.symbol().equals(value))
                    : pairs.read().stream().anyMatch(pair -> pair.symbol().equals(value));
            case "orderList" -> Set.of("open", "all").contains(value);
            default -> false;
        };
        if (!valid) throw new IllegalArgumentException("不支持的偏好参数或值");
        repository.save(key, value);
        return Map.of(key, value);
    }
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> invalid() { return Map.of("error", "不支持的偏好参数或值"); }
    @ExceptionHandler(DataAccessException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Map<String, String> unavailable() { return Map.of("error", "偏好数据库暂不可用，请稍后重试"); }
}
