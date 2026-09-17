package com.exchange.matching.mock.entity;

import jakarta.persistence.*;
import com.baomidou.mybatisplus.annotation.*;

@Entity
@Table(name = "mock_book_strategy", uniqueConstraints = @UniqueConstraint(columnNames = "symbol"))
@TableName("mock_book_strategy")
public class BookStrategyEntity {
    @Id @TableId(type = IdType.INPUT) @Column(length = 36) public String id;
    @Column(nullable = false, length = 32) public String symbol;
    @Column(nullable = false) public Integer depth;
    @org.hibernate.annotations.ColumnDefault("'1'")
    @Column(name = "quantity_multiplier", nullable = false, length = 32) public String quantityMultiplier = "1";
    @org.hibernate.annotations.ColumnDefault("''")
    @Column(name = "snapshot_id", nullable = false, length = 36) public String snapshotId = "";
    @Column(name = "snapshot_data", columnDefinition = "longtext") public String snapshotData;
    @Column(nullable = false, length = 24) public String phase;
    @Column(nullable = false, columnDefinition = "longtext") public String plan;
    @Column(name = "cancel_plan", columnDefinition = "longtext") public String cancelPlan;
    @Column(name = "source_update_id", length = 32) public String sourceUpdateId;
    @Column(name = "checked_at", length = 40) public String checkedAt;
    @Column(nullable = false, length = 512) public String message;
}
