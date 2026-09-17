package com.exchange.matching.mock.repository;
import com.exchange.matching.mock.entity.*;
import com.exchange.matching.mock.mapper.*;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.util.*;
public class TradeStrategyRepository {
 private final TradeStrategyMapper strategies; private final TradeReplayMapper records;
 public TradeStrategyRepository(TradeStrategyMapper strategies,TradeReplayMapper records){this.strategies=strategies;this.records=records;}
 public List<TradeStrategyEntity> list(){return strategies.selectList(new QueryWrapper<TradeStrategyEntity>().eq("deleted",false).orderByAsc("symbol"));}
 public TradeStrategyEntity require(String id){var row=strategies.selectById(id);if(row==null||row.deleted)throw new IllegalArgumentException("策略不存在");return row;}
 public TradeStrategyEntity bySymbol(String symbol,String mode){return strategies.selectOne(new QueryWrapper<TradeStrategyEntity>().eq("symbol",symbol).and(q-> {if("FOLLOW".equals(mode))q.eq("mode",mode).or().isNull("mode");else q.eq("mode",mode);}));}
 public void save(TradeStrategyEntity row){if(strategies.selectById(row.id)==null)strategies.insert(row);else strategies.updateById(row);}
 public TradeReplayEntity record(String id){return records.selectById(id);}
 public void insert(TradeReplayEntity row){records.insert(row);}
 public void saveRecord(TradeReplayEntity row){records.updateById(row);}
 public void cleanupCancelled(){records.delete(new QueryWrapper<TradeReplayEntity>().eq("status","CANCELLED"));}
 public List<TradeReplayEntity> pending(){return records.selectList(new QueryWrapper<TradeReplayEntity>().in("status",List.of("PENDING","BROKER_CONFIRMED","PENDING_PUBLISH")).orderByAsc("trade_time").last("LIMIT 100"));}
 public List<TradeReplayEntity> records(String id,int page){if(page<1||page>1000000)throw new IllegalArgumentException("页码无效");return records.selectList(new QueryWrapper<TradeReplayEntity>().eq("strategy_id",id).orderByDesc("trade_time","command_id").last("LIMIT 50 OFFSET "+((page-1)*50)));}
 public long count(String id){return records.selectCount(new QueryWrapper<TradeReplayEntity>().eq("strategy_id",id));}
 public boolean awaiting(String id){return records.selectCount(new QueryWrapper<TradeReplayEntity>().eq("strategy_id",id).in("status",List.of("PENDING","BROKER_CONFIRMED","PENDING_PUBLISH")))>0;}
 public Map<String,Long> stats(String id){
  return Map.of("accepted",count(id),"submitted",records.selectCount(new QueryWrapper<TradeReplayEntity>().eq("strategy_id",id).ne("order_id","").notIn("status",List.of("PENDING","PENDING_PUBLISH"))),
   "matched",records.selectCount(new QueryWrapper<TradeReplayEntity>().eq("strategy_id",id).apply("CAST(filled_quantity AS DECIMAL(38,8)) > 0")),
   "failed",records.selectCount(new QueryWrapper<TradeReplayEntity>().eq("strategy_id",id).in("status",List.of("REJECTED","FAILED"))));
 }
}

