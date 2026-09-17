package com.exchange.matching.mock.entity;

import jakarta.persistence.*;
import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;

/** JPA defines the schema; MyBatis Plus performs all record operations. */
@Entity
@Table(name = "mock_command_log")
@TableName("mock_command_log")
public class CommandLogEntity {
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

    @Column(name = "action", nullable = false, length = 16)
    public String action;

    @Column(name = "side", nullable = true, length = 8)
    public String side;

    @Column(name = "order_type", nullable = false, length = 16)
    public String orderType;

    @Column(name = "time_in_force", nullable = false, length = 8)
    public String timeInForce;

    @Column(name = "price_ticks", nullable = false)
    public Long priceTicks;

    @Column(name = "quantity_lots", nullable = false)
    public Long quantityLots;

    @Column(name = "confirmed", nullable = false)
    public Boolean confirmed;

    @Column(name = "payload", nullable = false, columnDefinition = "longtext")
    public String payload;

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp(6) default current_timestamp(6)", insertable = false, updatable = false)
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    public LocalDateTime createdAt;
}

