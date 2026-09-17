package com.exchange.matching.core.orderbook;

import com.exchange.matching.protocol.command.OrderCommand;
import com.exchange.matching.protocol.event.MarketUpdate;
import com.exchange.matching.protocol.event.MatchingOutcome;
import com.exchange.matching.protocol.event.OrderResult;
import com.exchange.matching.protocol.model.BookSnapshot;
import com.exchange.matching.protocol.model.OrderType;
import com.exchange.matching.protocol.model.TimeInForce;
import com.exchange.matching.protocol.model.Trade;

import java.util.*;

import static com.exchange.matching.protocol.command.OrderCommand.Action.CANCEL;
import static com.exchange.matching.protocol.command.OrderCommand.Side.BUY;
import static com.exchange.matching.protocol.event.OrderResult.Status.*;

/** Single-writer price/time priority matcher. Prices and quantities use integer units. */
public final class MatchingEngine {
    private final Map<String, OrderBook> books = new HashMap<>();
    private final Map<String, OrderCommand> commands = new HashMap<>();
    private final Map<String, MatchingOutcome> outcomes = new HashMap<>();
    private final Set<Long> usedOrderIds = new HashSet<>();
    private final Map<String, List<Trade>> latestTrades = new HashMap<>();
    private long sequence;
    private EngineParameters parameters = EngineParameters.defaults();

    /** A read-only refresh for subscribers that reconnect after missing Kafka updates. */
    public synchronized List<MarketUpdate> marketSnapshots() {
        return books.entrySet().stream().map(entry -> new MarketUpdate(
                "snapshot-" + entry.getKey() + "-" + sequence,
                snapshot(entry.getKey(), entry.getValue(), sequence), latestTrades.getOrDefault(entry.getKey(), List.of()))).toList();
    }

    public synchronized Map<String, OrderBookDepth> bookDepths() {
        var depths = new HashMap<String, OrderBookDepth>();
        books.forEach((symbol, book) -> depths.put(symbol, new OrderBookDepth(
                book.bidOrderCount, book.bids.size(), book.askOrderCount, book.asks.size())));
        return Map.copyOf(depths);
    }

    public synchronized void configure(EngineParameters next) {
        validateConfiguration(next);
        parameters = next;
    }

    public synchronized void validateConfiguration(EngineParameters next) {
        Objects.requireNonNull(next, "parameters");
        for (var entry : books.entrySet()) {
            if (!next.symbols().contains(entry.getKey()) && !entry.getValue().ordersById.isEmpty()) {
                throw new IllegalArgumentException("交易对仍有挂单，请先撤单：" + entry.getKey());
            }
        }
    }

    public synchronized void validate(OrderCommand command) {
        Objects.requireNonNull(command, "command").validate();
        OrderCommand previous = commands.get(command.commandId());
        if (previous != null && !previous.equals(command)) {
            throw new IllegalArgumentException("commandId was reused with different content");
        }
    }

    public synchronized boolean hasProcessed(String commandId) {
        return outcomes.containsKey(commandId);
    }

    public synchronized MatchingOutcome process(OrderCommand command) {
        validate(command);
        MatchingOutcome previous = outcomes.get(command.commandId());
        if (previous != null) return previous;
        boolean supported = parameters.symbols().contains(command.symbol());
        OrderBook book = supported ? books.computeIfAbsent(command.symbol(), ignored -> new OrderBook()) : new OrderBook();
        OrderResult result = !supported ? reject(command, "Trading pair is not enabled: " + command.symbol())
                : command.action() == CANCEL ? cancel(book, command)
                : command.quantityLots() < parameters.minOrderLots() || command.quantityLots() > parameters.maxOrderLots()
                ? reject(command, "Order quantity is outside configured lot range") : place(book, command);
        BookSnapshot snapshot = snapshot(command.symbol(), book, ++sequence);
        if (!result.trades().isEmpty()) latestTrades.put(command.symbol(), result.trades());
        MatchingOutcome outcome = new MatchingOutcome(result,
                new MarketUpdate(command.commandId(), snapshot, latestTrades.getOrDefault(command.symbol(), List.of())));
        commands.put(command.commandId(), command);
        outcomes.put(command.commandId(), outcome);
        return outcome;
    }

    private OrderResult place(OrderBook book, OrderCommand c) {
        if (usedOrderIds.contains(c.orderId())) return reject(c, "Duplicate orderId");
        NavigableMap<Long, PriceLevel> own = c.side() == BUY ? book.bids : book.asks;
        boolean mayRest = c.orderType() == OrderType.LIMIT && c.timeInForce() == TimeInForce.GTC;
        PriceLevel existing = own.get(c.priceTicks());
        // Validate before any mutation; conservatively reject an overflowing level.
        if (mayRest && existing != null && existing.totalRemainingLots > Long.MAX_VALUE - c.quantityLots()) {
            return reject(c, "Price level quantity overflow");
        }
        usedOrderIds.add(c.orderId());
        NavigableMap<Long, PriceLevel> opposite = c.side() == BUY ? book.asks : book.bids;
        // Preflight under the same single-writer lock: an unfillable FOK must never mutate makers.
        if (c.timeInForce() == TimeInForce.FOK && !canFill(opposite, c)) {
            return new OrderResult(c.commandId(), c.orderId(), c.symbol(), EXPIRED,
                    0, c.quantityLots(), "Insufficient liquidity for FOK", List.of());
        }
        long remaining = c.quantityLots();
        List<Trade> trades = new ArrayList<>();
        while (remaining > 0 && !opposite.isEmpty()) {
            PriceLevel level = opposite.firstEntry().getValue();
            if (!crosses(c, level.priceTicks)) break;
            OrderNode maker = level.head;
            long filled = Math.min(remaining, maker.remainingLots);
            remaining -= filled;
            maker.remainingLots -= filled;
            level.totalRemainingLots -= filled;
            trades.add(new Trade(c.commandId() + ":" + trades.size(), c.symbol(), maker.orderId,
                    c.orderId(), level.priceTicks, filled));
            if (maker.remainingLots == 0) unlink(book, opposite, maker);
        }
        if (remaining > 0 && mayRest) {
            PriceLevel level = own.computeIfAbsent(c.priceTicks(), price -> {
                PriceLevel created = new PriceLevel();
                created.priceTicks = price;
                return created;
            });
            OrderNode node = new OrderNode();
            node.orderId = c.orderId();
            node.side = c.side();
            node.remainingLots = remaining;
            node.level = level;
            node.previous = level.tail;
            if (level.tail == null) level.head = node;
            else level.tail.next = node;
            level.tail = node;
            level.totalRemainingLots += remaining;
            book.ordersById.put(node.orderId, node);
            if (node.side == BUY) book.bidOrderCount++; else book.askOrderCount++;
        }
        if (remaining > 0 && !mayRest) {
            return new OrderResult(c.commandId(), c.orderId(), c.symbol(), EXPIRED, 0, remaining,
                    "Unfilled quantity cancelled", trades);
        }
        return new OrderResult(c.commandId(), c.orderId(), c.symbol(),
                remaining == 0 ? FILLED : trades.isEmpty() ? OPEN : PARTIALLY_FILLED,
                remaining, 0, null, trades);
    }

    private boolean crosses(OrderCommand command, long price) {
        return command.orderType() == OrderType.MARKET
                || (command.side() == BUY ? price <= command.priceTicks() : price >= command.priceTicks());
    }

    private boolean canFill(NavigableMap<Long, PriceLevel> opposite, OrderCommand command) {
        long required = command.quantityLots();
        for (PriceLevel level : opposite.values()) {
            if (!crosses(command, level.priceTicks)) break;
            if (level.totalRemainingLots >= required) return true;
            required -= level.totalRemainingLots;
        }
        return false;
    }

    private OrderResult cancel(OrderBook book, OrderCommand c) {
        OrderNode node = book.ordersById.get(c.orderId());
        if (node == null) return reject(c, "Order is not open for this symbol");
        long cancelled = node.remainingLots;
        node.level.totalRemainingLots -= cancelled;
        unlink(book, node.side == BUY ? book.bids : book.asks, node);
        return new OrderResult(c.commandId(), c.orderId(), c.symbol(), CANCELLED,
                0, cancelled, null, List.of());
    }

    private void unlink(OrderBook book, NavigableMap<Long, PriceLevel> levels, OrderNode node) {
        PriceLevel level = node.level;
        if (node.previous == null) level.head = node.next;
        else node.previous.next = node.next;
        if (node.next == null) level.tail = node.previous;
        else node.next.previous = node.previous;
        book.ordersById.remove(node.orderId);
        if (node.side == BUY) book.bidOrderCount--; else book.askOrderCount--;
        if (level.head == null) levels.remove(level.priceTicks);
    }

    private OrderResult reject(OrderCommand c, String reason) {
        return new OrderResult(c.commandId(), c.orderId(), c.symbol(), REJECTED, 0, 0, reason, List.of());
    }

    private BookSnapshot snapshot(String symbol, OrderBook book, long seq) {
        return new BookSnapshot(symbol, seq, levels(book.bids), levels(book.asks));
    }

    private List<BookSnapshot.Level> levels(NavigableMap<Long, PriceLevel> levels) {
        return levels.values().stream()
                .map(level -> new BookSnapshot.Level(level.priceTicks, level.totalRemainingLots)).toList();
    }
}
