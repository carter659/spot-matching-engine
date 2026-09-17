package com.exchange.matching.mock.service;

import com.exchange.matching.mock.dto.PlaceRequest;
import com.exchange.matching.mock.dto.CancelRequest;
import com.exchange.matching.mock.dto.Receipt;
import com.exchange.matching.mock.dto.OrderView;
import com.exchange.matching.mock.dto.LevelView;
import com.exchange.matching.mock.dto.TradeView;
import com.exchange.matching.mock.dto.MarketView;
import com.exchange.matching.mock.dto.TradingState;

import com.exchange.matching.mock.market.MarketTradeHistory;
import com.exchange.matching.mock.repository.MockInbox;
import com.exchange.matching.mock.repository.MockOutbox;
import com.exchange.matching.protocol.command.OrderCommand;
import com.exchange.matching.protocol.event.MarketUpdate;
import com.exchange.matching.protocol.event.OrderResult;
import com.exchange.matching.protocol.model.BookSnapshot;
import com.exchange.matching.protocol.model.OrderType;
import com.exchange.matching.protocol.model.TimeInForce;
import com.exchange.matching.protocol.model.Trade;
import com.exchange.matching.protocol.model.TradingPair;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

@Service
@Profile("messaging")
public final class TradingService {
    public static final String SYMBOL = "BTC_USDT";
    public static final int PRICE_SCALE = 2;
    public static final int QUANTITY_SCALE = 4;
    private final MockOutbox outbox;
    private final MockInbox inbox;
    private final Map<String, MarketUpdate> market;
    private final MarketTradeHistory history;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.exchange.matching.mock.repository.TradingPairRepository pairs;

    public TradingService(MockOutbox outbox, MockInbox inbox, Map<String, MarketUpdate> latestMarket,
                          MarketTradeHistory history) {
        this.outbox = outbox;
        this.inbox = inbox;
        this.market = latestMarket;
        this.history = history;
    }

    public Receipt place(PlaceRequest request) throws IOException {
        // Serialize precision edits with the first persisted order for this mock instance.
        if (pairs != null) { synchronized (pairs) { return placeWithPrecision(request); } }
        return placeWithPrecision(request);
    }

    private Receipt placeWithPrecision(PlaceRequest request) throws IOException {
        if (request == null || request.orderType() == null) {
            throw new IllegalArgumentException("请选择有效的交易对和订单类型");
        }
        validateCommandId(request.commandId());
        TradingPair pair = pairs == null ? TradingPair.require(request.symbol()) : pairs.require(request.symbol(), false);
        long quantity = units(request.quantity(), pair.quantityScale(), "数量");
        long price;
        if (request.orderType() == OrderType.MARKET) {
            if (request.price() != null && request.price().signum() != 0) {
                throw new IllegalArgumentException("市价单不能指定委托价格");
            }
            price = 0;
        } else {
            price = units(request.price(), pair.priceScale(), "价格");
        }
        OrderCommand command = outbox.submitPlace(request.commandId(), orderId ->
                new OrderCommand(request.commandId(), OrderCommand.Action.PLACE, orderId,
                        pair.symbol(), request.side(), price, quantity, request.orderType(), request.timeInForce()));
        return new Receipt(command.commandId(), Long.toString(command.orderId()), "BROKER_CONFIRMED");
    }

    public Receipt cancel(long orderId, CancelRequest request) throws IOException {
        if (request == null) throw new IllegalArgumentException("撤单请求不能为空");
        validateCommandId(request.commandId());
        OrderCommand original = outbox.entries().stream().map(MockOutbox.Entry::command)
                .filter(c -> c.action() == OrderCommand.Action.PLACE && c.orderId() == orderId)
                .findFirst().orElseThrow(() -> new IllegalArgumentException("未找到该测试订单"));
        OrderCommand command = new OrderCommand(request.commandId(), OrderCommand.Action.CANCEL,
                orderId, original.symbol(), null, 0, 0);
        outbox.submit(command);
        return new Receipt(command.commandId(), Long.toString(orderId), "BROKER_CONFIRMED");
    }

    public TradingState state() { return state(SYMBOL); }

    public TradingState state(String symbol) {
        TradingPair pair = pairs == null ? TradingPair.require(symbol) : pairs.require(symbol, true);
        List<MockOutbox.Entry> entries = outbox.entries();
        List<OrderResult> results = inbox.results();
        Map<String, OrderResult> byCommand = new HashMap<>();
        Map<Long, Long> fills = new HashMap<>();
        Set<String> trades = new HashSet<>();
        for (OrderResult result : results) {
            byCommand.put(result.commandId(), result);
            for (Trade trade : result.trades()) {
                if (trades.add(trade.tradeId())) {
                    fills.merge(trade.makerOrderId(), trade.quantityLots(), Math::addExact);
                    fills.merge(trade.takerOrderId(), trade.quantityLots(), Math::addExact);
                }
            }
        }
        Map<Long, Long> cancellations = new HashMap<>();
        Set<Long> pendingCancels = new HashSet<>();
        Map<Long, String> cancelFailures = new HashMap<>();
        for (MockOutbox.Entry entry : entries) {
            OrderCommand c = entry.command();
            if (c.action() != OrderCommand.Action.CANCEL) continue;
            OrderResult result = byCommand.get(c.commandId());
            if (result == null) pendingCancels.add(c.orderId());
            else if (result.status() == OrderResult.Status.CANCELLED) {
                cancellations.merge(c.orderId(), result.cancelledLots(), Math::addExact);
            } else if (result.status() == OrderResult.Status.REJECTED) {
                cancelFailures.put(c.orderId(), result.reason());
            }
        }
        List<OrderView> orders = new ArrayList<>();
        for (MockOutbox.Entry entry : entries) {
            OrderCommand c = entry.command();
            if (c.action() != OrderCommand.Action.PLACE || !symbol.equals(c.symbol())) continue;
            OrderResult result = byCommand.get(c.commandId());
            boolean rejected = result != null && result.status() == OrderResult.Status.REJECTED;
            long filled = rejected ? 0 : fills.getOrDefault(c.orderId(), 0L);
            long cancelled = rejected || result == null ? 0
                    : Math.addExact(result.cancelledLots(), cancellations.getOrDefault(c.orderId(), 0L));
            long remaining = result == null || rejected ? 0 : Math.max(0, c.quantityLots() - filled - cancelled);
            String status = result == null ? entry.confirmed() ? "BROKER_CONFIRMED" : "PENDING_PUBLISH"
                    : rejected ? "REJECTED"
                    : result.status() == OrderResult.Status.EXPIRED ? "EXPIRED"
                    : cancelled > 0 ? "CANCELLED"
                    : remaining == 0 ? "FILLED"
                    : filled > 0 ? "PARTIALLY_FILLED" : "OPEN";
            boolean cancelPending = pendingCancels.contains(c.orderId()) && remaining > 0;
            String reason = result == null ? null : result.reason();
            if (cancelFailures.containsKey(c.orderId())) reason = "撤单被拒绝：" + cancelFailures.get(c.orderId());
            orders.add(new OrderView(Long.toString(c.orderId()), c.commandId(), c.symbol(), c.side().name(),
                    c.orderType().name(), c.timeInForce().name(),
                    c.orderType() == OrderType.MARKET ? null : decimal(c.priceTicks(), pair.priceScale()),
                    decimal(c.quantityLots(), pair.quantityScale()), decimal(filled, pair.quantityScale()),
                    decimal(remaining, pair.quantityScale()), decimal(cancelled, pair.quantityScale()), status, reason,
                    result != null && !rejected && remaining > 0 && !cancelPending, cancelPending));
        }
        Collections.reverse(orders);
        MarketUpdate update = market.get(symbol);
        MarketView view = update == null ? null : new MarketView(Long.toString(update.orderBook().sequence()),
                levels(update.orderBook().bids(), pair), levels(update.orderBook().asks(), pair),
                update.latestTrades().stream().map(t -> new TradeView(t.tradeId(), Long.toString(t.makerOrderId()),
                        Long.toString(t.takerOrderId()), decimal(t.priceTicks(), pair.priceScale()),
                        decimal(t.quantityLots(), pair.quantityScale()), null)).toList());
        List<TradeView> recentTrades = history.latest(symbol).stream().map(received -> {
            Trade t = received.trade();
            return new TradeView(t.tradeId(), Long.toString(t.makerOrderId()), Long.toString(t.takerOrderId()),
                    decimal(t.priceTicks(), pair.priceScale()), decimal(t.quantityLots(), pair.quantityScale()),
                    received.receivedAt().toString());
        }).toList();
        return new TradingState(symbol, pair.baseAsset(), pair.quoteAsset(), pair.priceScale(), pair.quantityScale(), List.copyOf(orders), view,
                recentTrades, entries.stream().filter(e -> !e.confirmed()).count(), Instant.now().toString());
    }

    private static List<LevelView> levels(List<BookSnapshot.Level> levels, TradingPair pair) {
        return levels.stream().map(l -> new LevelView(decimal(l.priceTicks(), pair.priceScale()),
                decimal(l.totalRemainingLots(), pair.quantityScale()))).toList();
    }
    private static String decimal(long value, int scale) { return BigDecimal.valueOf(value, scale).toPlainString(); }
    private static long units(BigDecimal value, int scale, String name) {
        if (value == null || value.signum() <= 0) throw new IllegalArgumentException(name + "必须大于 0");
        try { return value.movePointRight(scale).longValueExact(); }
        catch (ArithmeticException error) { throw new IllegalArgumentException(name + "最多支持 " + scale + " 位小数，且不能超出整数范围"); }
    }
    private static void validateCommandId(String id) {
        if (id == null || id.isBlank() || id.length() > 128) throw new IllegalArgumentException("commandId 无效");
    }
}
