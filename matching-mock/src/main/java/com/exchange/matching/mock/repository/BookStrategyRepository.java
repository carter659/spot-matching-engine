package com.exchange.matching.mock.repository;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.exchange.matching.mock.entity.BookStrategyEntity;
import com.exchange.matching.mock.mapper.BookStrategyMapper;
import java.util.List;

public class BookStrategyRepository {
    private final BookStrategyMapper mapper;
    public BookStrategyRepository(BookStrategyMapper mapper) { this.mapper = mapper; }
    public List<BookStrategyEntity> list() { return mapper.selectList(new QueryWrapper<BookStrategyEntity>().orderByAsc("symbol")); }
    public BookStrategyEntity require(String id) {
        var row = mapper.selectById(id);
        if (row == null) throw new IllegalArgumentException("策略不存在");
        return row;
    }
    public void add(BookStrategyEntity row) {
        try { mapper.insert(row); }
        catch (org.springframework.dao.DuplicateKeyException duplicate) { throw new IllegalArgumentException("该交易对策略已存在"); }
    }
    public void save(BookStrategyEntity row) {
        if (mapper.updateById(row) != 1) throw new IllegalStateException("策略保存失败");
    }
    public void delete(String id) { mapper.deleteById(id); }
    public void recover() {
        for (var row : list()) {
            if (!row.phase.equals("PAUSED") && !row.phase.equals("DELETING")) {
                if (!row.phase.equals("CANCELLING")) row.phase = "WATCHING";
                row.message = "服务重启，保留原委托并继续按档位差异同步";
                save(row);
            }
        }
    }
}
