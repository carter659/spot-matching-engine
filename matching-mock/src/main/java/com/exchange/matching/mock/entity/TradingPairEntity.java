package com.exchange.matching.mock.entity;

import jakarta.persistence.*;
import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;

/** JPA defines the schema; MyBatis Plus performs all record operations. */
@Entity
@Table(name = "mock_trading_pair")
@TableName("mock_trading_pair")
public class TradingPairEntity {
    @Column(name = "deleted", nullable = false, columnDefinition = "boolean default false")
    public Boolean deleted = false;
    @Id @TableId(value = "symbol", type = IdType.INPUT)
    @Column(name = "symbol", nullable = false, length = 32)
    public String symbol;

    @Column(name = "base_asset", nullable = false, length = 16)
    public String baseAsset;

    @Column(name = "quote_asset", nullable = false, length = 16)
    public String quoteAsset;

    @Column(name = "price_scale", nullable = false)
    public Integer priceScale;

    @Column(name = "quantity_scale", nullable = false)
    public Integer quantityScale;

    @Column(name = "example_price", nullable = false, length = 64)
    public String examplePrice;

    @Column(name = "sort_order", nullable = false)
    public Integer sortOrder;

    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamp(6) default current_timestamp(6)", insertable = false, updatable = false)
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    public LocalDateTime updatedAt;
}
