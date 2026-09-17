package com.exchange.matching.mock.service;

import com.exchange.matching.mock.dto.*;
import com.exchange.matching.mock.entity.BookStrategyEntity;
import com.exchange.matching.mock.market.BinanceOrderBookClient;
import com.exchange.matching.mock.repository.*;
import com.exchange.matching.protocol.model.*;
import com.exchange.matching.protocol.command.OrderCommand;
import java.math.*;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** One local mock owns strategy execution; transitions and plan writes precede side effects. */
public class BookStrategyService {
    private final BookStrategyRepository repository;
    private final TradingPairRepository pairs;
    private final TradingService trading;
    private final MockOutbox outbox;
    private final BinanceOrderBookClient source;
    private final StrategyHistoryRepository history;
    private final JsonMapper json = JsonMapper.builder().build();
    public BookStrategyService(BookStrategyRepository repository, TradingPairRepository pairs,
            TradingService trading, MockOutbox outbox, BinanceOrderBookClient source, StrategyHistoryRepository history) {
        this.repository = repository; this.pairs = pairs; this.trading = trading; this.outbox = outbox; this.source = source;
        this.history = history;
        for (var row : repository.list()) { archive(row); sync(row, row.phase.equals("PAUSED")); }
    }
    public synchronized List<BookStrategyEntity> list() { return repository.list(); }
    public synchronized void add(BookStrategyRequest request) {
        pairs.require(request.symbol(), false);
        validate(request);
        if (repository.list().size() >= 20) throw new IllegalArgumentException("最多添加 20 个策略");
        var row = new BookStrategyEntity();
        row.id = UUID.randomUUID().toString(); row.symbol = request.symbol(); row.depth = request.depth();
        row.quantityMultiplier = request.quantityMultiplier().stripTrailingZeros().toPlainString();
        row.phase = "PAUSED"; row.plan = "[]"; row.message = "已添加，点击启动加载币安盘口";
        repository.add(row);
    }
    public synchronized void start(String id) {
        var row = repository.require(id);
        if (!row.phase.equals("PAUSED")) throw new IllegalArgumentException("策略尚未暂停或撤单尚未完成");
        pairs.require(row.symbol, false);
        archive(row);
        row.phase = "STARTING"; row.plan = "[]"; row.cancelPlan = "[]"; row.snapshotId = ""; row.message = "运行中：等待加载币安盘口";
        repository.save(row);
    }
    private void validate(BookStrategyRequest request) {
        if (request.depth() < 1 || request.depth() > 50) throw new IllegalArgumentException("每侧盘口档数为 1–50");
        if (request.quantityMultiplier().compareTo(new BigDecimal("0.00000001")) < 0
                || request.quantityMultiplier().compareTo(new BigDecimal("1000")) > 0
                || request.quantityMultiplier().stripTrailingZeros().scale() > 8)
            throw new IllegalArgumentException("数量倍率为 0.00000001–1000，最多 8 位小数");
    }
    public synchronized void update(String id, BookStrategyRequest request) {
        validate(request); var row = repository.require(id);
        if (!row.symbol.equals(request.symbol())) throw new IllegalArgumentException("修改参数不改变交易对，请另建策略");
        if (row.phase.equals("DELETING")) throw new IllegalArgumentException("策略正在删除");
        archive(row); row.depth = request.depth(); row.quantityMultiplier = request.quantityMultiplier().stripTrailingZeros().toPlainString();
        row.snapshotData = ""; // Force the next snapshot to capture the changed configuration.
        if (!row.phase.equals("PAUSED") && !row.phase.equals("CANCELLING")) row.phase = "WATCHING";
        row.message = "参数已保存；下一次采样仅调整有差异的档位"; repository.save(row);
    }
    public synchronized List<com.exchange.matching.mock.entity.StrategySnapshotEntity> snapshots(String id) { return history.snapshots(id); }
    public synchronized List<com.exchange.matching.mock.entity.StrategyOrderEntity> snapshotOrders(String snapshotId) { return history.orders(snapshotId); }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString = "${mock.strategy.cleanup-delay-ms:10000}", initialDelay = 10000)
    public synchronized void cleanupCancelledHistory() {
        var states = new HashMap<String, List<OrderView>>();
        var protectedCommands = new HashSet<String>();
        for (var row : repository.list()) {
            var views = states.computeIfAbsent(row.symbol, symbol -> trading.state(symbol).orders());
            var cancelled = new HashSet<String>();
            views.stream().filter(view -> view.status().equals("CANCELLED")).forEach(view -> cancelled.add(view.commandId()));
            var plan = orders(row);
            history.sync(plan, outbox.entries(), views, row.phase.equals("PAUSED"));
            var remaining = plan.stream().filter(order -> !cancelled.contains(order.commandId())).toList();
            if (remaining.size() != plan.size()) {
                row.plan = json.writeValueAsString(remaining);
                var pending = row.cancelPlan == null ? new HashSet<String>() : json.readValue(row.cancelPlan,
                        new tools.jackson.core.type.TypeReference<HashSet<String>>() {});
                pending.removeAll(cancelled); row.cancelPlan = json.writeValueAsString(pending);
                if (remaining.isEmpty()) { row.snapshotId = ""; row.snapshotData = ""; }
                // Commit reference removal before deleting history. Retrying after a crash cannot resurrect orders.
                repository.save(row);
            }
            remaining.forEach(order -> protectedCommands.add(order.commandId()));
        }
        history.purgeCancelled(protectedCommands);
    }
    public synchronized void pause(String id, boolean delete) {
        var row = repository.require(id);
        if (row.phase.equals("DELETING")) return;
        row.phase = delete ? "DELETING" : "CANCELLING";
        row.message = delete ? "删除中：等待策略挂单撤销回执" : "暂停中：等待策略挂单撤销回执";
        repository.save(row);
    }
    @org.springframework.scheduling.annotation.Scheduled(fixedRateString = "${mock.strategy.poll-delay-ms:1000}")
    public synchronized void tick() {
        // Check every active pair each round, rather than one pair every N rounds.
        for (var row : repository.list()) {
            if (row.phase.equals("PAUSED")) continue;
            try {
                archive(row); sync(row, false);
                if (row.phase.equals("DELETING") || row.phase.equals("CANCELLING")) { cancel(row); continue; }
                var book = source.read(row.symbol, row.depth);
                reconcile(row, book);
            } catch (Exception failure) {
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                // Keep persisted order identities and cancellation intent for an idempotent retry.
                row.message = "保持运行，等待重试：" + failure.getClass().getSimpleName() + " " + Objects.toString(failure.getMessage(), "");
                if (row.message.length() > 500) row.message = row.message.substring(0, 500);
                repository.save(row);
            }
        }
    }
    private String key(StrategyOrder order) { return order.side() + ":" + order.price().stripTrailingZeros().toPlainString(); }
    private boolean pending(OrderView view) {
        return view == null || view.status().equals("PENDING_PUBLISH") || view.status().equals("BROKER_CONFIRMED");
    }
    private void reconcile(BookStrategyEntity row, BinanceOrderBookClient.Snapshot book) throws Exception {
        var desired = new LinkedHashMap<String, StrategyOrder>();
        plan(pairs.require(row.symbol, false), book, row.depth, new BigDecimal(row.quantityMultiplier))
                .forEach(order -> desired.put(key(order), order));
        var views = new HashMap<String, OrderView>();
        trading.state(row.symbol).orders().forEach(view -> views.put(view.commandId(), view));
        var entries = new HashMap<String, MockOutbox.Entry>();
        outbox.entries().forEach(entry -> entries.put(entry.command().commandId(), entry));
        var cancelling = row.cancelPlan == null ? new HashSet<String>() : json.readValue(row.cancelPlan,
                new tools.jackson.core.type.TypeReference<HashSet<String>>() {});
        var retained = new ArrayList<StrategyOrder>();
        var dropped = new ArrayList<StrategyOrder>();
        for (var order : orders(row)) {
            var view = views.get(order.commandId()); var entry = entries.get(order.commandId());
            var target = desired.get(key(order));
            if (entry != null && !pending(view) && !view.canCancel() && !view.cancelPending()) {
                dropped.add(order); cancelling.remove(order.commandId()); continue;
            }
            BigDecimal remaining = pending(view) ? order.quantity() : new BigDecimal(view.remainingQuantity());
            boolean same = target != null && remaining.compareTo(target.quantity()) == 0;
            // Once a cancel was persisted/sent, do not reuse that order even if Binance reverts the level.
            boolean cancelSent = entry != null && entries.containsKey("strategy-cancel-" + order.commandId());
            if (same && !cancelling.contains(order.commandId()) && !cancelSent && (view == null || !view.cancelPending())) {
                retained.add(order); desired.remove(key(order));
            } else if (entry == null) {
                dropped.add(order); cancelling.remove(order.commandId());
            } else {
                retained.add(order); cancelling.add(order.commandId());
            }
        }
        history.sync(dropped, outbox.entries(), new ArrayList<>(views.values()), true);
        // Wait only for the old order at this level (or a crossing old opposite level).
        // A busy Binance book must not starve all new levels while unrelated cancels are in flight.
        for (var target : desired.values()) {
            boolean blocked = retained.stream().anyMatch(old -> key(old).equals(key(target))
                    || old.side() != target.side() && (target.side() == OrderCommand.Side.BUY
                    ? target.price().compareTo(old.price()) >= 0 : target.price().compareTo(old.price()) <= 0));
            if (!blocked) retained.add(target);
        }
        String nextPlan = json.writeValueAsString(retained);
        boolean changed = !nextPlan.equals(row.plan) || !book.lastUpdateId().equals(row.sourceUpdateId)
                || !json.writeValueAsString(book).equals(row.snapshotData);
        row.plan = nextPlan; row.cancelPlan = json.writeValueAsString(cancelling);
        row.sourceUpdateId = book.lastUpdateId(); row.snapshotData = json.writeValueAsString(book);
        row.checkedAt = Instant.now().toString(); row.phase = "WATCHING";
        if (changed || row.snapshotId == null || row.snapshotId.isEmpty()) row.snapshotId = UUID.randomUUID().toString();
        row.message = cancelling.isEmpty() ? "运行中：仅同步差异档位，未变化委托保持原 ID" : "运行中：仅撤销差异档位，等待回执后补单";
        repository.save(row); archive(row);
        Exception publishFailure = null;
        // One failed level must not starve the other side of the order book.
        for (var order : retained) {
            try {
                var entry = entries.get(order.commandId());
                if (cancelling.contains(order.commandId())) {
                    var view = views.get(order.commandId());
                    if (pending(view)) { if (!entry.confirmed()) outbox.submit(entry.command()); }
                    else trading.cancel(entry.command().orderId(), new CancelRequest("strategy-cancel-" + order.commandId()));
                } else if (entry == null) {
                    trading.place(new PlaceRequest(order.commandId(), row.symbol, order.side(), OrderType.LIMIT,
                            TimeInForce.GTC, order.price(), order.quantity()));
                } else if (!entry.confirmed()) outbox.submit(entry.command());
            } catch (Exception failure) { publishFailure = failure; }
        }
        sync(row, false);
        if (publishFailure != null) throw publishFailure;
    }
    private List<StrategyOrder> orders(BookStrategyEntity row) {
        return json.readValue(row.plan, new tools.jackson.core.type.TypeReference<List<StrategyOrder>>() {});
    }
    private void archive(BookStrategyEntity row) {
        var plan = orders(row); if (plan.isEmpty() && (row.snapshotId == null || row.snapshotId.isEmpty())) return;
        if (row.snapshotId == null || row.snapshotId.isEmpty()) {
            row.snapshotId = UUID.nameUUIDFromBytes((row.id + ":" + plan.getFirst().commandId()).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
            repository.save(row);
        }
        history.archive(row, plan);
    }
    private void sync(BookStrategyEntity row, boolean finish) {
        var plan = orders(row); if (plan.isEmpty()) return;
        history.sync(plan, outbox.entries(), trading.state(row.symbol).orders(), finish);
    }
    private void cancel(BookStrategyEntity row) throws Exception {
        var commands = new HashSet<String>();
        orders(row).forEach(o -> commands.add(o.commandId()));
        // Only durable commands belonging to this strategy are cancellable; manual orders are untouched.
        var owned = outbox.entries().stream().filter(e -> commands.contains(e.command().commandId())).toList();
        if (!owned.isEmpty()) {
            var views = new HashMap<String, OrderView>();
            trading.state(row.symbol).orders().forEach(o -> views.put(o.commandId(), o));
            boolean waiting = false;
            for (var entry : owned) {
                var view = views.get(entry.command().commandId());
                if (view == null || view.status().equals("PENDING_PUBLISH") || view.status().equals("BROKER_CONFIRMED")) {
                    if (!entry.confirmed()) outbox.submit(entry.command());
                    waiting = true; continue;
                }
                if (view.status().equals("OPEN") || view.status().equals("PARTIALLY_FILLED")) {
                    waiting = true;
                    trading.cancel(entry.command().orderId(), new CancelRequest("strategy-cancel-" + entry.command().commandId()));
                }
            }
            sync(row, !waiting);
            if (waiting) return;
        }
        sync(row, true);
        if (row.phase.equals("DELETING")) repository.delete(row.id);
        else {
            row.phase = "PAUSED";
            row.message = row.message.substring(0, Math.min(row.message.length(), 380))
                    + "；已暂停，策略订单均已结束，再次启动将重新加载盘口";
            repository.save(row);
        }
    }
    public static List<StrategyOrder> plan(TradingPair pair, BinanceOrderBookClient.Snapshot book, int depth) {
        return plan(pair, book, depth, BigDecimal.ONE);
    }
    public static List<StrategyOrder> plan(TradingPair pair, BinanceOrderBookClient.Snapshot book, int depth, BigDecimal multiplier) {
        var result = new ArrayList<StrategyOrder>();
        for (var side : OrderCommand.Side.values()) {
            var levels = side == OrderCommand.Side.BUY ? book.bids() : book.asks();
            var prices = new LinkedHashMap<BigDecimal, BigDecimal>();
            for (var level : levels.stream().limit(depth).toList()) {
                if (level.size() != 2) throw new IllegalArgumentException("盘口档位格式无效");
                var price = new BigDecimal(level.get(0)).setScale(pair.priceScale(), side == OrderCommand.Side.BUY ? RoundingMode.FLOOR : RoundingMode.CEILING);
                var quantity = new BigDecimal(level.get(1)).multiply(multiplier).setScale(pair.quantityScale(), RoundingMode.DOWN);
                if (price.signum() <= 0 || quantity.signum() <= 0) continue;
                prices.merge(price, quantity, BigDecimal::add);
            }
            for (var level : prices.entrySet()) {
                level.getKey().movePointRight(pair.priceScale()).longValueExact();
                level.getValue().movePointRight(pair.quantityScale()).longValueExact();
                result.add(new StrategyOrder(UUID.randomUUID().toString(), side, level.getKey(), level.getValue()));
            }
        }
        // Alternate the sides so a long first placement batch does not leave one side waiting behind all others.
        var bids = result.stream().filter(o -> o.side() == OrderCommand.Side.BUY).toList();
        var asks = result.stream().filter(o -> o.side() == OrderCommand.Side.SELL).toList();
        var balanced = new ArrayList<StrategyOrder>();
        for (int i = 0; i < Math.max(bids.size(), asks.size()); i++) {
            if (i < bids.size()) balanced.add(bids.get(i));
            if (i < asks.size()) balanced.add(asks.get(i));
        }
        return balanced;
    }
}
