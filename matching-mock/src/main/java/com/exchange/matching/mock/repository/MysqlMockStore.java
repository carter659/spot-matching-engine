package com.exchange.matching.mock.repository;

import com.exchange.matching.protocol.event.OrderResult;
import com.exchange.matching.mock.entity.CommandLogEntity;
import com.exchange.matching.mock.entity.ResultLogEntity;
import com.exchange.matching.mock.mapper.CommandLogMapper;
import com.exchange.matching.mock.mapper.ResultLogMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.util.List;

/** Each mapper write commits before command publication or result acknowledgement. */
public final class MysqlMockStore {
    private final CommandLogMapper commands;
    private final ResultLogMapper results;
    private final JsonMapper json = JsonMapper.builder().build();
    public MysqlMockStore(CommandLogMapper commands, ResultLogMapper results) {
        this.commands = commands; this.results = results;
    }
    public RecordStore<MockOutbox.Entry> commands() {
        return new RecordStore<>() {
            public List<MockOutbox.Entry> readAll() throws IOException {
                try {
                    return commands.selectList(new QueryWrapper<CommandLogEntity>().orderByAsc("id")).stream()
                            .map(row -> json.readValue(row.payload, MockOutbox.Entry.class)).toList();
                } catch (RuntimeException failure) { throw new IOException("Failed to restore mock commands", failure); }
            }
            public void append(MockOutbox.Entry entry) throws IOException {
                var command = entry.command();
                try {
                    var row = new CommandLogEntity();
                    row.commandId = command.commandId(); row.orderId = command.orderId(); row.symbol = command.symbol();
                    row.action = command.action().name(); row.side = command.side() == null ? null : command.side().name();
                    row.orderType = command.orderType().name(); row.timeInForce = command.timeInForce().name();
                    row.priceTicks = command.priceTicks(); row.quantityLots = command.quantityLots();
                    row.confirmed = entry.confirmed(); row.payload = json.writeValueAsString(entry);
                    commands.insert(row);
                } catch (RuntimeException failure) { throw new IOException("Failed to save mock command", failure); }
            }
        };
    }
    public RecordStore<OrderResult> results() {
        return new RecordStore<>() {
            public List<OrderResult> readAll() throws IOException {
                try {
                    return results.selectList(new QueryWrapper<ResultLogEntity>().orderByAsc("id")).stream()
                            .map(row -> json.readValue(row.payload, OrderResult.class)).toList();
                } catch (RuntimeException failure) { throw new IOException("Failed to restore mock results", failure); }
            }
            public void append(OrderResult result) throws IOException {
                try {
                    var row = new ResultLogEntity();
                    row.commandId = result.commandId(); row.orderId = result.orderId(); row.symbol = result.symbol();
                    row.status = result.status().name(); row.remainingLots = result.remainingLots();
                    row.cancelledLots = result.cancelledLots(); row.reason = result.reason(); row.payload = json.writeValueAsString(result);
                    results.insert(row);
                } catch (RuntimeException failure) { throw new IOException("Failed to save mock result", failure); }
            }
        };
    }
}
