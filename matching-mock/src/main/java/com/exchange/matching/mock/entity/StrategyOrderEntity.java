package com.exchange.matching.mock.entity;
import jakarta.persistence.*;
import com.baomidou.mybatisplus.annotation.*;

@Entity @Table(name="mock_strategy_order", indexes={@Index(name="idx_strategy_order",columnList="strategy_id"),@Index(name="idx_snapshot_order",columnList="snapshot_id")})
@TableName("mock_strategy_order")
public class StrategyOrderEntity {
    @Id @TableId(type=IdType.INPUT) @Column(name="command_id",length=36) public String commandId;
    @Column(name="strategy_id",nullable=false,length=36) public String strategyId;
    @Column(name="snapshot_id",nullable=false,length=36) public String snapshotId;
    @Column(nullable=false,length=32) public String symbol;
    @Column(nullable=false,length=8) public String side;
    @Column(nullable=false,length=64) public String price;
    @Column(nullable=false,length=64) public String quantity;
    @Column(name="order_id",nullable=false,length=32) public String orderId = "";
    @Column(nullable=false,length=32) public String status = "PLANNED";
    @Column(name="updated_at",nullable=false,length=40) public String updatedAt;
}
