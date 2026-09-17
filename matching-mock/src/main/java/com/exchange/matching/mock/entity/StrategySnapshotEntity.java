package com.exchange.matching.mock.entity;
import jakarta.persistence.*;
import com.baomidou.mybatisplus.annotation.*;

@Entity @Table(name="mock_strategy_snapshot", indexes=@Index(name="idx_strategy_snapshot",columnList="strategy_id,created_at"))
@TableName("mock_strategy_snapshot")
public class StrategySnapshotEntity {
    @Id @TableId(type=IdType.INPUT) @Column(length=36) public String id;
    @Column(name="strategy_id",nullable=false,length=36) public String strategyId;
    @Column(nullable=false,length=32) public String symbol;
    @Column(nullable=false) public Integer depth;
    @Column(name="quantity_multiplier",nullable=false,length=32) public String quantityMultiplier;
    @Column(name="source_update_id",length=32) public String sourceUpdateId;
    @Column(nullable=false,columnDefinition="longtext") public String data;
    @Column(nullable=false,columnDefinition="longtext") public String plan;
    @Column(name="created_at",nullable=false,length=40) public String createdAt;
}
