package com.exchange.matching.mock.service;
import com.exchange.matching.mock.dto.*;
import com.exchange.matching.mock.entity.ScenarioRunEntity;
import com.exchange.matching.mock.mapper.ScenarioRunMapper;
import com.exchange.matching.mock.repository.*;
import com.exchange.matching.protocol.command.OrderCommand.Side;
import com.exchange.matching.protocol.model.*;
import com.exchange.matching.protocol.event.OrderResult;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

@Service @Profile("database & messaging")
public class ScenarioService {
 private final TradingService trading; private final MockInbox inbox; private final TradingPairRepository pairs; private final ScenarioRunMapper mapper;
 private final Set<String> active = new HashSet<>();
 public ScenarioService(TradingService trading, MockInbox inbox, TradingPairRepository pairs, ScenarioRunMapper mapper) {this.trading=trading;this.inbox=inbox;this.pairs=pairs;this.mapper=mapper;}
 public List<ScenarioRunEntity> list(){return mapper.selectList(new QueryWrapper<ScenarioRunEntity>().orderByDesc("created").last("LIMIT 50"));}
 @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
 public synchronized void recover(){
  for(var run:mapper.selectList(new QueryWrapper<ScenarioRunEntity>().eq("status","RUNNING"))){
   if(active.contains(run.symbol))continue;
   run.status="INTERRUPTED";run.report+="服务重启中断；请按批次检查挂单，未自动重新下单。\n";mapper.updateById(run);
  }
 }
 public synchronized ScenarioRunEntity start(String symbol,String scenario,boolean isolated){
  if(!isolated) throw new IllegalArgumentException("请确认使用独立空盘口，且已停止该交易对的其他下单来源");
  if(!Set.of("PRICE","FIFO","PARTIAL","CANCEL","IOC","FOK","DUPLICATE").contains(scenario)) throw new IllegalArgumentException("未知场景");
  var pair=pairs.require(symbol,false);
  var state=trading.state(symbol);
  if(active.contains(symbol)||state.orders().stream().anyMatch(o->o.canCancel()||o.cancelPending()||Set.of("BROKER_CONFIRMED","PENDING_PUBLISH").contains(o.status())) || (state.market()!=null&&(!state.market().asks().isEmpty()||!state.market().bids().isEmpty()))) throw new IllegalArgumentException("交易对有挂单、待处理命令或正在回放，请使用独立空盘口");
  var run=new ScenarioRunEntity();run.id=UUID.randomUUID().toString();run.symbol=symbol;run.scenario=scenario;run.created=Instant.now().toString();run.status="RUNNING";run.report="";mapper.insert(run);active.add(symbol);
  Thread.ofVirtual().name("scenario-"+run.id).start(()->execute(run,pair));return run;
 }
 private void execute(ScenarioRunEntity run,TradingPair pair){
  var ctx=new Context(run,pair);String result="PASSED";
  try {
   long a=ctx.place(Side.SELL,100,2,TimeInForce.GTC,"OPEN",2,0,List.of());
   switch(run.scenario){
    case "PRICE" -> {long b=ctx.place(Side.SELL,99,2,TimeInForce.GTC,"OPEN",2,0,List.of());ctx.place(Side.BUY,100,3,TimeInForce.IOC,"FILLED",0,0,List.of(new Fill(b,99,2),new Fill(a,100,1)));}
    case "FIFO" -> {long b=ctx.place(Side.SELL,100,2,TimeInForce.GTC,"OPEN",2,0,List.of());ctx.place(Side.BUY,100,3,TimeInForce.IOC,"FILLED",0,0,List.of(new Fill(a,100,2),new Fill(b,100,1)));}
    case "PARTIAL" -> ctx.place(Side.BUY,100,1,TimeInForce.GTC,"FILLED",0,0,List.of(new Fill(a,100,1)));
    case "CANCEL" -> {ctx.cancel(a,2);ctx.place(Side.BUY,100,1,TimeInForce.IOC,"EXPIRED",0,1,List.of());}
    case "IOC" -> ctx.place(Side.BUY,100,3,TimeInForce.IOC,"EXPIRED",0,1,List.of(new Fill(a,100,2)));
    case "FOK" -> {ctx.place(Side.BUY,100,3,TimeInForce.FOK,"EXPIRED",0,3,List.of());ctx.place(Side.BUY,100,2,TimeInForce.FOK,"FILLED",0,0,List.of(new Fill(a,100,2)));}
    case "DUPLICATE" -> {var original=ctx.first;var receipt=trading.place(original);if(!receipt.orderId().equals(Long.toString(a)))throw new IllegalStateException("重复命令返回不同订单 ID");ctx.log("重复命令", "相同订单 ID",receipt.orderId(),true);ctx.place(Side.BUY,100,3,TimeInForce.IOC,"EXPIRED",0,1,List.of(new Fill(a,100,2)));}
   }
  }catch(Exception e){result="FAILED";ctx.log("执行失败","全部步骤符合预期",String.valueOf(e.getMessage()),false);}
  finally {
   for(String command:ctx.commands)try {
    var order=trading.state(run.symbol).orders().stream().filter(o->o.commandId().equals(command)).findFirst();
    if(order.isPresent()&&(order.get().canCancel()||Set.of("BROKER_CONFIRMED","PENDING_PUBLISH").contains(order.get().status()))) {
     String id=run.id+"-cleanup-"+order.get().orderId();trading.cancel(Long.parseLong(order.get().orderId()),new CancelRequest(id));var reply=ctx.await(id);
     if(reply.status()!=OrderResult.Status.CANCELLED)throw new IllegalStateException(reply.reason());
     ctx.log("清理挂单", "撤销本批次剩余委托",order.get().orderId(),true);
    }
   }catch(Exception e){result="CLEANUP_REQUIRED";ctx.log("清理未确认","请在交易页检查本批次订单",String.valueOf(e.getMessage()),false);}
   run.status=result;mapper.updateById(run);synchronized(this){active.remove(run.symbol);}
  }
 }
 record Fill(long maker,long price,long quantity){}
 private class Context {
  final ScenarioRunEntity run;final TradingPair pair;final List<String> commands=new ArrayList<>();PlaceRequest first;int step;
  Context(ScenarioRunEntity run,TradingPair pair){this.run=run;this.pair=pair;}
  void log(String action,String expected,String actual,boolean pass){run.report+=action+" | 预期："+expected+" | 实际："+actual+" | "+(pass?"通过":"失败")+"\n";mapper.updateById(run);}
  OrderResult await(String id)throws Exception{long deadline=System.nanoTime()+30_000_000_000L;while(System.nanoTime()<deadline){var r=inbox.results().stream().filter(x->x.commandId().equals(id)).findFirst();if(r.isPresent())return r.get();Thread.sleep(100);}throw new IllegalStateException("等待可靠回执超时："+id);}
  long place(Side side,long price,long qty,TimeInForce tif,String status,long remaining,long cancelled,List<Fill> fills)throws Exception{
   String id=run.id+"-"+(++step);commands.add(id);
   var req=new PlaceRequest(id,run.symbol,side,OrderType.LIMIT,tif,BigDecimal.valueOf(price,pair.priceScale()),BigDecimal.valueOf(qty,pair.quantityScale()));if(first==null)first=req;
   log("步骤 "+step+" "+side+" "+tif,"价格 ticks="+price+" 数量 lots="+qty,"待投递 commandId="+id,true);
   var receipt=trading.place(req);var r=await(id);
   var actual=r.trades().stream().map(t->new Fill(t.makerOrderId(),t.priceTicks(),t.quantityLots())).toList();
   boolean ok=r.status().name().equals(status)&&r.remainingLots()==remaining&&r.cancelledLots()==cancelled&&actual.equals(fills)&&r.trades().stream().allMatch(t->t.takerOrderId()==r.orderId());
   log("回执 "+receipt.orderId(),status+" 剩余="+remaining+" 取消="+cancelled+" 成交="+fills,r.status()+" 剩余="+r.remainingLots()+" 取消="+r.cancelledLots()+" 成交="+actual,ok);
   if(!ok)throw new IllegalStateException("步骤 "+step+" 与预期不一致；已停止后续步骤");return r.orderId();
  }
  void cancel(long order,long quantity)throws Exception{String id=run.id+"-"+(++step);trading.cancel(order,new CancelRequest(id));var r=await(id);boolean ok=r.status()==OrderResult.Status.CANCELLED&&r.cancelledLots()==quantity;log("撤单 "+order,"CANCELLED 数量="+quantity,r.status()+" 数量="+r.cancelledLots(),ok);if(!ok)throw new IllegalStateException("撤单不符合预期");}
 }
}
