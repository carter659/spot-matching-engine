package com.exchange.matching.mock.entity;

import jakarta.persistence.*;
import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;

/** JPA defines the schema; MyBatis Plus performs all record operations. */
@Entity
@Table(name = "mock_result_log")
@TableName("mock_result_log")
public class ResultLogEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @TableId(value = "id", type = IdType.AUTO)
    @Column(name = "id", nullable = false)
    public Long id;

    @Column(name = "command_id", nullable = false, columnDefinition = "text")
    public String commandId;

    @Column(name = "order_id", nullable = false)
    public Long orderId;

    @Column(name = "symbol", nullable = false, columnDefinition = "text")
    public String symbol;

    @Column(name = "status", nullable = false, length = 32)
    public String status;

    @Column(name = "remaining_lots", nullable = false)
    public Long remainingLots;

    @Column(name = "cancelled_lots", nullable = false)
    public Long cancelledLots;

    @Column(name = "reason", nullable = true, columnDefinition = "text")
    public String reason;

    @Column(name = "payload", nullable = false, columnDefinition = "longtext")
    public String payload;

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp(6) default current_timestamp(6)", insertable = false, updatable = false)
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    public LocalDateTime createdAt;
}

