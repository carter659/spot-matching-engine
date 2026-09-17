package com.exchange.matching.protocol.model;

import java.util.List;

/** Shared simulation instruments; example prices are test inputs, not market quotes. */
public record TradingPair(String symbol, String baseAsset, String quoteAsset, int priceScale,
                          int quantityScale, String examplePrice) {
    private static TradingPair pair(String base, int scale, String price) {
        return new TradingPair(base + "_USDT", base, "USDT", scale, 4, price);
    }
    public static final List<TradingPair> CATALOG = List.of(
            pair("BTC", 2, "65000.00"), pair("ETH", 2, "3000.00"), pair("BNB", 2, "500.00"),
            pair("SOL", 2, "150.00"), pair("XRP", 6, "0.500000"), pair("DOGE", 6, "0.100000"),
            pair("ADA", 6, "0.400000"), pair("TRX", 6, "0.100000"), pair("AVAX", 2, "30.00"),
            pair("LINK", 4, "15.0000"), pair("DOT", 4, "5.0000"), pair("LTC", 2, "80.00"),
            pair("BCH", 2, "400.00"), pair("UNI", 4, "8.0000"), pair("ATOM", 4, "7.0000"),
            pair("NEAR", 4, "4.0000"), pair("APT", 4, "8.0000"), pair("ARB", 6, "0.800000"),
            pair("OP", 4, "2.0000"), pair("SHIB", 8, "0.00002000"));

    public static TradingPair require(String symbol) {
        return CATALOG.stream().filter(pair -> pair.symbol().equals(symbol)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown trading pair: " + symbol));
    }
}
