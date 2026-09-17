package com.exchange.matching.mock.market;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;
class BinanceTradeStreamLiveTests {
 @Test @EnabledIfSystemProperty(named="binance.stream.live",matches="true")
 void receivesPublicAggregateTradesWithoutPlacingOrders() throws Exception {
  try(var source=new BinanceTradeStream()) {
   long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(25);
   while(System.nanoTime()<deadline){var trades=source.poll("BTC_USDT");if(!trades.isEmpty()){assertTrue(trades.getFirst().price().signum()>0);return;}Thread.sleep(500);}
   fail("No public trade received: "+source.status("BTC_USDT"));
  }
 }
}
