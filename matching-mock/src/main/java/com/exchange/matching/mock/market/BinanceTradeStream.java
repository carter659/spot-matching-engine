package com.exchange.matching.mock.market;
import java.net.URI;
import java.net.http.*;
import java.math.BigDecimal;
import java.time.Duration;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import tools.jackson.databind.json.JsonMapper;

/** Public market-only WebSocket. Bounded buffers never send orders to Binance. */
public class BinanceTradeStream implements AutoCloseable {
 public record Trade(long id,long time,BigDecimal price,BigDecimal quantity,boolean buyerMaker) {}
 private final Map<String,Connection> connections=new ConcurrentHashMap<>();
 private HttpClient client;
 private synchronized HttpClient client(){if(client==null)client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();return client;}
 public List<Trade> poll(String symbol){
  if(!symbol.matches("[A-Z0-9]{1,16}_USDT"))throw new IllegalArgumentException("交易对格式无效");
  var c=connections.computeIfAbsent(symbol,Connection::new);c.ensure();var items=new ArrayList<Trade>();c.queue.drainTo(items,1000);return items;
 }
 public long dropped(String symbol){var c=connections.get(symbol);return c==null?0:c.dropped.getAndSet(0);}
 public String status(String symbol){var c=connections.get(symbol);return c==null?"未连接":c.status;}
 public void stop(String symbol){var c=connections.remove(symbol);if(c!=null)c.close();}
 public void close(){connections.keySet().forEach(this::stop);if(client!=null)client.shutdownNow();}
 public static Trade parse(String payload,String symbol){
  var n=JsonMapper.builder().build().readTree(payload);
  if(!"aggTrade".equals(n.path("e").asText())||!symbol.replace("_","").equals(n.path("s").asText())
    ||!n.path("m").isBoolean()||!n.path("a").canConvertToLong()||!n.path("T").canConvertToLong())throw new IllegalArgumentException("无效成交消息");
  var t=new Trade(n.path("a").asLong(),n.path("T").asLong(),new BigDecimal(n.path("p").asText()),new BigDecimal(n.path("q").asText()),n.path("m").asBoolean());
  if(t.id()<0||t.time()<=0||t.price().signum()<=0||t.quantity().signum()<=0)throw new IllegalArgumentException("无效成交数值");return t;
 }
 private class Connection implements WebSocket.Listener {
  final String symbol;final ArrayBlockingQueue<Trade> queue=new ArrayBlockingQueue<>(1000);final AtomicLong dropped=new AtomicLong();
  final StringBuilder fragments=new StringBuilder();volatile String status="等待连接";volatile WebSocket socket;volatile boolean closed;
  CompletableFuture<WebSocket> connecting;long nextAttempt;volatile long connectedAt,lastActivity;volatile int backoff=5;
  Connection(String symbol){this.symbol=symbol;}
  synchronized void ensure(){
   long now=System.currentTimeMillis();if(closed)return;
   if(socket!=null){if(now-connectedAt<23*3600000L&&now-lastActivity<90000)return;socket.abort();socket=null;}
   if(connecting!=null&&!connecting.isDone()||now<nextAttempt)return;
   nextAttempt=now+backoff*1000L;backoff=Math.min(60,backoff*2);status="连接中";
   connecting=client().newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5)).buildAsync(URI.create("wss://data-stream.binance.vision/ws/"+symbol.replace("_","").toLowerCase(Locale.ROOT)+"@aggTrade"),this);
   connecting.whenComplete((ws,error)->{if(error!=null)status="连接失败，自动重试";});
  }
  public void onOpen(WebSocket ws){if(closed){ws.abort();return;}fragments.setLength(0);socket=ws;connectedAt=lastActivity=System.currentTimeMillis();backoff=5;status="已连接";ws.request(1);}
  public CompletionStage<?> onText(WebSocket ws,CharSequence data,boolean last){
   if(closed||socket!=ws)return null;
   lastActivity=System.currentTimeMillis();fragments.append(data);
   if(fragments.length()>32768){fragments.setLength(0);ws.abort();socket=null;status="消息过大，重新连接";return null;}
   if(last){try{var trade=parse(fragments.toString(),symbol);if(!closed&&!queue.offer(trade)){queue.poll();dropped.incrementAndGet();queue.offer(trade);}}catch(RuntimeException invalid){status="忽略无效行情";}finally{fragments.setLength(0);}}
   ws.request(1);return null;
  }
  public CompletionStage<?> onPing(WebSocket ws,ByteBuffer payload){lastActivity=System.currentTimeMillis();ws.request(1);return ws.sendPong(payload);}
  public CompletionStage<?> onClose(WebSocket ws,int code,String reason){if(socket==ws)socket=null;status="连接断开，自动重连";return null;}
  public void onError(WebSocket ws,Throwable failure){if(socket==ws)socket=null;status="网络异常，自动重连";}
  synchronized void close(){closed=true;if(connecting!=null)connecting.cancel(true);if(socket!=null)socket.abort();queue.clear();}
 }
}
