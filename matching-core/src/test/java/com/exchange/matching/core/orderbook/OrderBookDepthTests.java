package com.exchange.matching.core.orderbook;
import com.exchange.matching.protocol.command.OrderCommand;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OrderBookDepthTests {
    OrderCommand order(String id,long orderId,OrderCommand.Side side,long price,long lots) {
        return new OrderCommand(id,OrderCommand.Action.PLACE,orderId,"BTC_USDT",side,price,lots);
    }
    @Test void countsOrdersAndDistinctPricesThroughPartialFillsCancellationAndDuplicateDelivery() {
        var engine=new MatchingEngine();
        var buy=order("buy1",1,OrderCommand.Side.BUY,100,10);
        engine.process(buy);engine.process(buy);
        engine.process(order("buy2",2,OrderCommand.Side.BUY,100,5));
        engine.process(order("buy3",3,OrderCommand.Side.BUY,90,8));
        engine.process(order("sell1",4,OrderCommand.Side.SELL,110,6));
        engine.process(order("sell2",5,OrderCommand.Side.SELL,120,7));
        assertEquals(new OrderBookDepth(3,2,2,2),engine.bookDepths().get("BTC_USDT"));
        engine.process(order("partial",6,OrderCommand.Side.SELL,100,4));
        assertEquals(new OrderBookDepth(3,2,2,2),engine.bookDepths().get("BTC_USDT"));
        engine.process(order("fill",7,OrderCommand.Side.SELL,100,11));
        assertEquals(new OrderBookDepth(1,1,2,2),engine.bookDepths().get("BTC_USDT"));
        engine.process(new OrderCommand("cancel",OrderCommand.Action.CANCEL,3,"BTC_USDT",null,0,0));
        assertEquals(new OrderBookDepth(0,0,2,2),engine.bookDepths().get("BTC_USDT"));
        engine.process(order("clear",8,OrderCommand.Side.BUY,120,13));
        assertEquals(OrderBookDepth.EMPTY,engine.bookDepths().get("BTC_USDT"));
    }
}
