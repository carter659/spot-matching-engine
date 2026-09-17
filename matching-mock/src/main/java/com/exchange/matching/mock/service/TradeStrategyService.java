package com.exchange.matching.mock.service;
import com.exchange.matching.mock.dto.*;
import com.exchange.matching.mock.entity.*;
import com.exchange.matching.mock.repository.*;
import com.exchange.matching.mock.market.BinanceTradeStream;
import com.exchange.matching.protocol.command.OrderCommand.Side;
import com.exchange.matching.protocol.model.*;
import java.math.*;
import java.time.Clock;
import java.util.*;

/** One executor per mock database; durable replay identity precedes RabbitMQ side effects. */
public class TradeStrategyService {
 private final TradeStrategyRepository repository;private final TradingPairRepository pairs;private final TradingService trading;private final BinanceTradeStream source;private final Clock clock;
 private final Map<String,long[]> rates=new HashMap<>();private final Map<String,String> errors=new HashMap<>();
 public TradeStrategyService(TradeStrategyRepository repository,TradingPairRepository pairs,TradingService trading,BinanceTradeStream source){this(repository,pairs,trading,source,Clock.systemUTC());}
 public TradeStrategyService(TradeStrategyRepository repository,TradingPairRepository pairs,TradingService trading,BinanceTradeStream source,Clock clock){this.repository=repository;this.pairs=pairs;this.trading=trading;this.source=source;this.clock=clock;}
 public synchronized List<Map<String,Object>> list(){return repository.list().stream().map(row->{Map<String,Object> v=new LinkedHashMap<>();v.put("config",row);v.put("stats",repository.stats(row.id));v.put("connection",row.running?source.status(row.symbol):"已暂停");v.put("message",errors.getOrDefault(row.id,""));return v;}).toList();}
 private void validate(TradeStrategyRequest r){
  if(r==null)throw new IllegalArgumentException("参数不能为空");pairs.require(r.symbol(),false);
  if(r.mode()!=null&&!Set.of("FOLLOW","SWEEP").contains(r.mode()))throw new IllegalArgumentException("未知策略模式");

  if(r.maxPerSecond()<1||r.maxPerSecond()>100||r.maxAgeSeconds()<1||r.maxAgeSeconds()>60)throw new IllegalArgumentException("每秒下单数 1–100，行情过期时间 1–60 秒");
  if(r.quantityMultiplier()==null||r.quantityMultiplier().compareTo(new BigDecimal("0.00000001"))<0||r.quantityMultiplier().compareTo(new BigDecimal("1000"))>0||r.quantityMultiplier().stripTrailingZeros().scale()>8)throw new IllegalArgumentException("数量倍率 0.00000001–1000，最多 8 位小数");
  if(r.maxQuantity()==null||r.maxQuantity().signum()<=0||r.maxQuantity().precision()>20||r.maxQuantity().stripTrailingZeros().scale()>8)throw new IllegalArgumentException("单笔最大数量必须为正数，最多 8 位小数");
  try{r.maxQuantity().movePointRight(pairs.require(r.symbol(),false).quantityScale()).longValueExact();}catch(ArithmeticException e){throw new IllegalArgumentException("单笔最大数量超出交易对精度或整数范围");}
 }
 private void configure(TradeStrategyEntity row,TradeStrategyRequest r){row.symbol=r.symbol();row.quantityMultiplier=r.quantityMultiplier().toPlainString();row.maxQuantity=r.maxQuantity().toPlainString();row.maxPerSecond=r.maxPerSecond();row.maxAgeSeconds=r.maxAgeSeconds();row.mode=Objects.toString(r.mode(),"FOLLOW");row.targetPrice=r.targetPrice()==null?null:r.targetPrice().toPlainString();row.sweepSide=r.sweepSide();}
 public synchronized void add(TradeStrategyRequest r){validate(r);var row=repository.bySymbol(r.symbol(),Objects.toString(r.mode(),"FOLLOW"));if(row!=null&&!row.deleted)throw new IllegalArgumentException("该交易对的此模式策略已存在");if(repository.list().size()>=40)throw new IllegalArgumentException("最多 40 个策略");if(row==null){row=new TradeStrategyEntity();row.id=UUID.randomUUID().toString();}configure(row,r);row.deleted=false;row.running=false;repository.save(row);}
 public synchronized void update(String id,TradeStrategyRequest r){validate(r);var row=repository.require(id);if(!row.symbol.equals(r.symbol()))throw new IllegalArgumentException("修改不能改变交易对");var duplicate=repository.bySymbol(r.symbol(),Objects.toString(r.mode(),"FOLLOW"));if(duplicate!=null&&!duplicate.id.equals(id))throw new IllegalArgumentException("该交易对的此模式策略已存在，请修改对应策略");configure(row,r);repository.save(row);}
 public synchronized void running(String id,boolean run){var row=repository.require(id);if(run){pairs.require(row.symbol,false);if("SWEEP".equals(row.mode)&&repository.awaiting(id))throw new IllegalArgumentException("上次扫单仍待可靠回执，请稍后启动");}row.running=run;repository.save(row);if(!run)stopIfUnused(row.symbol);}
 public synchronized void delete(String id){var row=repository.require(id);row.running=false;row.deleted=true;repository.save(row);stopIfUnused(row.symbol);}
 public synchronized Map<String,Object> records(String id,int page){return Map.of("total",repository.count(id),"records",repository.records(id,page));}
 @org.springframework.scheduling.annotation.Scheduled(fixedDelay=10000)
 public synchronized void cleanupCancelled(){repository.cleanupCancelled();}
 @org.springframework.scheduling.annotation.Scheduled(fixedRate=1000,scheduler="tradeStrategyScheduler")
 public synchronized void tick(){
  errors.clear();
  var views=new HashMap<String,Map<String,OrderView>>();
  for(var replay:repository.pending()){
   try{
    var byId=views.computeIfAbsent(replay.symbol,s->{var m=new HashMap<String,OrderView>();trading.state(s).orders().forEach(v->m.put(v.commandId(),v));return m;});
    var view=byId.get(replay.commandId);
    if(view!=null){replay.orderId=view.orderId();replay.filledQuantity=view.filledQuantity();replay.status=view.status();replay.message=Objects.toString(view.reason(),"");repository.saveRecord(replay);}
    if(replay.status.equals("PENDING")||replay.status.equals("PENDING_PUBLISH"))publish(replay);
   }catch(Exception e){errors.put(replay.strategyId,"投递重试："+e.getClass().getSimpleName());}
  }
  var batches=new HashMap<String,List<BinanceTradeStream.Trade>>();var drops=new HashMap<String,Long>();
  for(var row:repository.list())if(row.running){try{
    var batch=batches.computeIfAbsent(row.symbol,source::poll);
    // Bound database work per second; a live strategy must not replay a backlog.
    int limit="SWEEP".equals(row.mode)?1:row.maxPerSecond;
    if(batch.size()>limit){int omitted=batch.size()-limit;row.received+=omitted;row.skipped+=omitted;batch=batch.subList(omitted,batch.size());}
    for(int i=0;i<batch.size();i++) {
     try { accept(row,batch.get(i)); }
     catch(Exception e) {
      errors.put(row.id,"投递异常，已受理命令自动重试，未受理行情跳过："+e.getClass().getSimpleName());
      for(int j=i+1;j<batch.size();j++)if(batch.get(j).id()>row.lastTradeId){row.received++;row.skipped++;row.lastTradeId=batch.get(j).id();}
      break;
     }
    }
    long dropped=drops.computeIfAbsent(row.symbol,source::dropped);row.received+=dropped;row.skipped+=dropped;repository.save(row);
   }catch(Exception e){errors.put(row.id,"等待自动重试："+e.getClass().getSimpleName());}}
 }
 public synchronized void accept(String id,BinanceTradeStream.Trade trade)throws Exception {var row=repository.require(id);accept(row,trade);}
 private void accept(TradeStrategyEntity row,BinanceTradeStream.Trade trade)throws Exception {
  if(!row.running||row.deleted||trade.id()<=row.lastTradeId)return;
  String commandId="replay-"+row.id+"-"+trade.id();var existing=repository.record(commandId);
  if(existing==null){var legacy=repository.record("replay-"+row.symbol+"-"+trade.id());if(legacy!=null&&legacy.strategyId.equals(row.id))existing=legacy;}
  if(existing!=null){row.lastTradeId=trade.id();repository.save(row);return;}
  row.received++;long now=clock.millis();var rate=rates.computeIfAbsent(row.id,k->new long[]{now/1000,0});if(rate[0]!=now/1000){rate[0]=now/1000;rate[1]=0;}
  boolean skip=trade.time()>now+5000||now-trade.time()>row.maxAgeSeconds*1000L||rate[1]>=row.maxPerSecond;
  var pair=pairs.require(row.symbol,false);var side=trade.buyerMaker()?Side.SELL:Side.BUY;
  var quantity=trade.quantity().multiply(new BigDecimal(row.quantityMultiplier)).min(new BigDecimal(row.maxQuantity)).setScale(pair.quantityScale(),RoundingMode.DOWN);
  var price=trade.price().setScale(pair.priceScale(),side==Side.BUY?RoundingMode.FLOOR:RoundingMode.CEILING);
  String bookSequence=null;
  if("SWEEP".equals(row.mode)) {
   var book=trading.state(row.symbol).market();quantity=BigDecimal.ZERO;
   skip|=repository.awaiting(row.id);
   var previous=repository.records(row.id,1);
   if(book!=null&&!previous.isEmpty()&&previous.getFirst().bookSequence!=null)skip|=new BigInteger(book.sequence()).compareTo(new BigInteger(previous.getFirst().bookSequence))<=0;
   if(!skip&&book!=null) {
    bookSequence=book.sequence();
    var asks=book.asks().stream().filter(l->new BigDecimal(l.price()).compareTo(trade.price())<=0).toList();
    var bids=book.bids().stream().filter(l->new BigDecimal(l.price()).compareTo(trade.price())>=0).toList();
    var levels=!asks.isEmpty()?asks:bids;side=!asks.isEmpty()?Side.BUY:Side.SELL;
    for(var level:levels){quantity=quantity.add(new BigDecimal(level.quantity()));price=new BigDecimal(level.price());}
    if(quantity.compareTo(new BigDecimal(row.maxQuantity))>0){quantity=BigDecimal.ZERO;errors.put(row.id,"整档扫单量超过单笔上限，请提高上限；未拆单投递");}
   }
  }
  skip|=quantity.signum()<=0||price.signum()<=0;
  if(skip){row.skipped++;row.lastTradeId=trade.id();repository.save(row);return;}
  price.movePointRight(pair.priceScale()).longValueExact();quantity.movePointRight(pair.quantityScale()).longValueExact();
  var record=new TradeReplayEntity();record.commandId=commandId;record.strategyId=row.id;record.symbol=row.symbol;record.sourceTradeId=Long.toString(trade.id());record.tradeTime=trade.time();record.side=side.name();record.sourcePrice=trade.price().toPlainString();record.sourceQuantity=trade.quantity().toPlainString();record.price=price.toPlainString();record.quantity=quantity.toPlainString();
  record.bookSequence=bookSequence;
  repository.insert(record);rate[1]++;row.lastTradeId=trade.id();repository.save(row);

  publish(record);
 }
 private void stopIfUnused(String symbol){if(repository.list().stream().noneMatch(s->s.running&&s.symbol.equals(symbol)))source.stop(symbol);}
 private void publish(TradeReplayEntity row)throws Exception{
  var receipt=trading.place(new PlaceRequest(row.commandId,row.symbol,Side.valueOf(row.side),OrderType.LIMIT,TimeInForce.IOC,new BigDecimal(row.price),new BigDecimal(row.quantity)));
  row.orderId=receipt.orderId();row.status="BROKER_CONFIRMED";row.message="已投递，等待撮合回执";repository.saveRecord(row);
 }
}




