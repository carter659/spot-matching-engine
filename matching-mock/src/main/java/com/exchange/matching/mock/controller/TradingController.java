package com.exchange.matching.mock.controller;

import com.exchange.matching.mock.dto.PlaceRequest;
import com.exchange.matching.mock.dto.CancelRequest;
import com.exchange.matching.mock.dto.Receipt;
import com.exchange.matching.mock.dto.TradingState;

import com.exchange.matching.mock.service.TradingService;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.util.Map;
import java.util.List;
import com.exchange.matching.protocol.model.TradingPair;

@RestController
@Profile("messaging")
@RequestMapping("/mock/trading")
public class TradingController {
    private final TradingService service;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.exchange.matching.mock.repository.TradingPairRepository pairRepository;
    public TradingController(TradingService service) { this.service = service; }

    @GetMapping
    public TradingState state(@RequestParam(defaultValue = "BTC_USDT") String symbol) { return service.state(symbol); }

    @GetMapping("/pairs")
    public List<TradingPair> pairs() { return pairRepository == null ? TradingPair.CATALOG : pairRepository.read(); }

    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Receipt place(@RequestBody PlaceRequest request) throws IOException {
        return service.place(request);
    }

    @PostMapping("/orders/{orderId}/cancel")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Receipt cancel(@PathVariable long orderId,
                                        @RequestBody CancelRequest request) throws IOException {
        return service.cancel(orderId, request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> invalid(IllegalArgumentException failure) {
        return Map.of("error", failure.getMessage(), "retryable", false);
    }

    @ExceptionHandler({IllegalStateException.class, IOException.class})
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Map<String, Object> unavailable(Exception failure) {
        return Map.of("error", "投递暂未确认，请使用原 commandId 重试；不要重复创建订单", "retryable", true);
    }
}
