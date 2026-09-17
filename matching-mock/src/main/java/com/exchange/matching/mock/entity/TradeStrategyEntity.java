package com.exchange.matching.mock.entity;
import jakarta.persistence.*;
import com.baomidou.mybatisplus.annotation.*;
@Entity @Table(name="mock_trade_strategy",uniqueConstraints=@UniqueConstraint(name="uk_trade_strategy_symbol_mode",columnNames={"symbol","mode"})) @TableName("mock_trade_strategy")
public class TradeStrategyEntity {
 @Id @TableId(type=IdType.INPUT) @Column(length=36) public String id;
 @Column(nullable=false,length=32) public String symbol;
 @Column(name="quantity_multiplier",nullable=false,length=32) public String quantityMultiplier;
 @Column(name="max_quantity",nullable=false,length=32) public String maxQuantity;
 @Column(name="max_per_second",nullable=false) public Integer maxPerSecond;
 @Column(name="max_age_seconds",nullable=false) public Integer maxAgeSeconds;
 @Column(nullable=false) public Boolean running=false;
 @Column(nullable=false) public Boolean deleted=false;
 public String mode;
 @Column(name="target_price") public String targetPrice;
 @Column(name="sweep_side") public String sweepSide;
 @Column(name="last_trade_id",nullable=false) public Long lastTradeId=-1L;
 @Column(nullable=false) public Long received=0L;
 @Column(nullable=false) public Long skipped=0L;
}

