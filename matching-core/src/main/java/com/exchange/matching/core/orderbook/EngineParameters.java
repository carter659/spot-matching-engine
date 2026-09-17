package com.exchange.matching.core.orderbook;

import java.util.Set;

public record EngineParameters(Set<String> symbols, long minOrderLots, long maxOrderLots) {
    public EngineParameters {
        if (symbols == null || symbols.isEmpty()) throw new IllegalArgumentException("至少启用一个交易对");
        symbols = Set.copyOf(symbols);
        symbols = symbols.stream().map(symbol -> symbol.trim().toUpperCase(java.util.Locale.ROOT)
                .replaceAll("\\s*/\\s*", "_").replace('-', '_')).collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (symbols.size() > 500 || symbols.stream().anyMatch(symbol -> symbol.length() > 32 || !symbol.matches("[A-Z0-9]+_[A-Z0-9]+")))
            throw new IllegalArgumentException("交易对格式须为 BTC_USDT，最多 500 个，每个最多 32 个字符");
        if (minOrderLots < 1 || maxOrderLots < minOrderLots) throw new IllegalArgumentException("数量范围无效");
    }
    public static EngineParameters defaults() { return new EngineParameters(Set.of("BTC_USDT"), 1, Long.MAX_VALUE); }
}
