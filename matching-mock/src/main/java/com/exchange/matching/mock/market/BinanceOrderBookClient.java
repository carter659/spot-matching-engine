package com.exchange.matching.mock.market;

import java.net.URI;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.List;
import java.util.Objects;
import tools.jackson.databind.json.JsonMapper;

/** Public market data only. Never calls Binance trading endpoints or accepts API credentials. */
public class BinanceOrderBookClient {
    public record Snapshot(String lastUpdateId, List<List<String>> bids, List<List<String>> asks) {}
    private final JsonMapper json = JsonMapper.builder().build();
    private Instant blockedUntil = Instant.MIN;
    public synchronized Snapshot read(String symbol, int depth) throws Exception {
        if (!symbol.matches("[A-Z0-9]{1,16}_USDT") || depth < 1 || depth > 50)
            throw new IllegalArgumentException("交易对或盘口档数无效");
        if (Instant.now().isBefore(blockedUntil)) throw new IllegalStateException("币安接口限流，正在等待重试");
        var connection = (HttpURLConnection) URI.create("https://data-api.binance.vision/api/v3/depth?symbol="
                + symbol.replace("_", "") + "&limit=" + depth).toURL().openConnection();
        connection.setConnectTimeout(5000); connection.setReadTimeout(5000); connection.setInstanceFollowRedirects(false);
        try {
        int status = connection.getResponseCode();
        if (status == 429 || status == 418) {
            long seconds = status == 418 ? 3600 : 60;
            try { seconds = Math.max(seconds, Long.parseLong(Objects.toString(connection.getHeaderField("Retry-After"), "0"))); }
            catch (NumberFormatException ignored) { }
            blockedUntil = Instant.now().plusSeconds(Math.min(seconds, 86400));
        }
        if (status != 200) throw new IllegalStateException("币安行情不可用，HTTP " + status);
        // Binance may include additional fields; only decode the required book fields.
        String body;
        try (var input = connection.getInputStream()) { body = new String(input.readNBytes(262144), StandardCharsets.UTF_8); }
        var root = json.readTree(body);
        var book = new Snapshot(root.path("lastUpdateId").asText(),
                json.convertValue(root.path("bids"), new tools.jackson.core.type.TypeReference<List<List<String>>>() {}),
                json.convertValue(root.path("asks"), new tools.jackson.core.type.TypeReference<List<List<String>>>() {}));
        if (!book.lastUpdateId().matches("[0-9]+") || book.bids() == null || book.asks() == null
                || book.bids().isEmpty() || book.asks().isEmpty()) throw new IllegalStateException("币安返回空盘口或无效序号");
        return book;
        } finally { connection.disconnect(); }
    }
}
