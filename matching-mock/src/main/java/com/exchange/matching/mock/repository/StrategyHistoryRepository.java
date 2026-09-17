package com.exchange.matching.mock.repository;
import com.exchange.matching.mock.entity.*;
import com.exchange.matching.mock.mapper.*;
import com.exchange.matching.mock.dto.*;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import java.time.Instant;
import java.util.*;

public class StrategyHistoryRepository {
    private final StrategySnapshotMapper snapshots;
    private final StrategyOrderMapper orders;
    private final tools.jackson.databind.json.JsonMapper json = tools.jackson.databind.json.JsonMapper.builder().build();
    private String cleanupCursor = "";
    public StrategyHistoryRepository(StrategySnapshotMapper snapshots, StrategyOrderMapper orders) { this.snapshots=snapshots; this.orders=orders; }
    public void archive(BookStrategyEntity strategy, List<StrategyOrder> plan) {
        if (snapshots.selectById(strategy.snapshotId)==null) {
            var row=new StrategySnapshotEntity(); row.id=strategy.snapshotId; row.strategyId=strategy.id;
            row.symbol=strategy.symbol; row.depth=strategy.depth; row.quantityMultiplier=strategy.quantityMultiplier;
            row.sourceUpdateId=strategy.sourceUpdateId; row.data=strategy.snapshotData==null?"{\"legacy\":true}":strategy.snapshotData;
            row.plan=strategy.plan; row.createdAt=Instant.now().toString(); snapshots.insert(row);
        }
        for(var order:plan) if(orders.selectById(order.commandId())==null) {
            var row=new StrategyOrderEntity(); row.commandId=order.commandId(); row.strategyId=strategy.id;
            row.snapshotId=strategy.snapshotId; row.symbol=strategy.symbol; row.side=order.side().name();
            row.price=order.price().toPlainString(); row.quantity=order.quantity().toPlainString();
            row.updatedAt=Instant.now().toString(); orders.insert(row);
        }
    }
    public void sync(List<StrategyOrder> plan, List<MockOutbox.Entry> entries, List<OrderView> views, boolean finish) {
        var ids=new HashSet<String>(); plan.forEach(o->ids.add(o.commandId()));
        var found=new HashMap<String,MockOutbox.Entry>(); entries.stream().filter(e->ids.contains(e.command().commandId())).forEach(e->found.put(e.command().commandId(),e));
        var states=new HashMap<String,OrderView>(); views.forEach(v->states.put(v.commandId(),v));
        for(var commandId:ids) {
            var entry=found.get(commandId); var view=states.get(commandId);
            String id=entry==null?"":Long.toString(entry.command().orderId());
            String state=view!=null?view.status():entry!=null?entry.confirmed()?"BROKER_CONFIRMED":"PENDING_PUBLISH":finish?"NOT_SUBMITTED":"PLANNED";
            var current=orders.selectById(commandId);
            if(current!=null && (!current.orderId.equals(id)||!current.status.equals(state))) orders.update(null,new UpdateWrapper<StrategyOrderEntity>()
                    .eq("command_id",commandId).set("order_id",id).set("status",state).set("updated_at",Instant.now().toString()));
        }
    }
    public List<StrategySnapshotEntity> snapshots(String strategyId) {
        return snapshots.selectList(new QueryWrapper<StrategySnapshotEntity>().eq("strategy_id",strategyId).orderByDesc("created_at").last("LIMIT 20"));
    }
    /** Plans still used by the executor are protected until its durable references are removed. */
    public void purgeCancelled(Set<String> protectedCommands) {
        var cancelled = new QueryWrapper<StrategyOrderEntity>().eq("status", "CANCELLED");
        if (!protectedCommands.isEmpty()) cancelled.notIn("command_id", protectedCommands);
        orders.delete(cancelled);

        // Bounded keyset scan: do not repeatedly scan the oldest live snapshots or block order processing.
        var page = snapshots.selectList(new QueryWrapper<StrategySnapshotEntity>().gt("id", cleanupCursor)
                .orderByAsc("id").last("LIMIT 100"));
        if (page.isEmpty()) { cleanupCursor = ""; return; }
        var plans = new LinkedHashMap<String, List<StrategyOrder>>();
        var commandIds = new HashSet<String>();
        for (var snapshot : page) {
            var plan = json.readValue(snapshot.plan, new tools.jackson.core.type.TypeReference<List<StrategyOrder>>() {});
            plans.put(snapshot.id, plan); plan.forEach(order -> commandIds.add(order.commandId()));
        }
        var surviving = new HashSet<String>(protectedCommands);
        var ids = new ArrayList<>(commandIds);
        for (int i = 0; i < ids.size(); i += 500) {
            orders.selectList(new QueryWrapper<StrategyOrderEntity>().select("command_id")
                    .in("command_id", ids.subList(i, Math.min(i + 500, ids.size()))))
                    .forEach(order -> surviving.add(order.commandId));
        }
        for (var snapshot : page) {
            var original = plans.get(snapshot.id);
            var remaining = original.stream().filter(order -> surviving.contains(order.commandId())).toList();
            if (remaining.isEmpty()) snapshots.deleteById(snapshot.id);
            else if (remaining.size() != original.size()) {
                snapshots.update(null, new UpdateWrapper<StrategySnapshotEntity>().eq("id", snapshot.id)
                        .set("plan", json.writeValueAsString(remaining)));
            }
        }
        cleanupCursor = page.getLast().id;
    }
    public List<StrategyOrderEntity> orders(String snapshotId) {
        var snapshot = snapshots.selectById(snapshotId);
        if (snapshot == null) return List.of();
        // Retained orders can belong to many snapshots. The immutable snapshot plan stores that association.
        var plan = tools.jackson.databind.json.JsonMapper.builder().build().readValue(snapshot.plan,
                new tools.jackson.core.type.TypeReference<List<StrategyOrder>>() {});
        if (plan.isEmpty()) return List.of();
        return orders.selectList(new QueryWrapper<StrategyOrderEntity>().in("command_id", plan.stream().map(StrategyOrder::commandId).toList())
                .orderByAsc("side","price"));
    }
}
