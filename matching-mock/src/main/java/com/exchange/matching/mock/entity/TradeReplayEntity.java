package com.exchange.matching.mock.entity;
import jakarta.persistence.*;
import com.baomidou.mybatisplus.annotation.*;
@Entity @Table(name="mock_trade_replay",indexes={@Index(name="idx_replay_strategy",columnList="strategy_id,trade_time"),@Index(name="idx_replay_status",columnList="status")}) @TableName("mock_trade_replay")
public class TradeReplayEntity {
 @Column(name="book_sequence",length=32) public String bookSequence;
 @Id @TableId(type=IdType.INPUT) @Column(name="command_id",length=80) public String commandId;
 @Column(name="strategy_id",nullable=false,length=36) public String strategyId;
 @Column(nullable=false,length=32) public String symbol;
 @Column(name="source_trade_id",nullable=false,length=32) public String sourceTradeId;
 @Column(name="trade_time",nullable=false) public Long tradeTime;
 @Column(nullable=false,length=8) public String side;
 @Column(name="source_price",nullable=false,length=64) public String sourcePrice;
 @Column(name="source_quantity",nullable=false,length=64) public String sourceQuantity;
 @Column(nullable=false,length=64) public String price;
 @Column(nullable=false,length=64) public String quantity;
 @Column(name="order_id",nullable=false,length=32) public String orderId="";
 @Column(name="filled_quantity",nullable=false,length=64) public String filledQuantity="0";
 @Column(nullable=false,length=32) public String status="PENDING";
 @Column(nullable=false,length=512) public String message="等待投递";
}

