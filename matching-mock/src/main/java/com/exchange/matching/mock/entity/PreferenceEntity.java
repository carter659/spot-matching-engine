package com.exchange.matching.mock.entity;

import jakarta.persistence.*;
import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;

/** JPA defines the schema; MyBatis Plus performs all record operations. */
@Entity
@Table(name = "mock_preference")
@TableName("mock_preference")
public class PreferenceEntity {
    @Id @TableId(value = "preference_key", type = IdType.INPUT)
    @Column(name = "preference_key", nullable = false, length = 40)
    public String preferenceKey;

    @Column(name = "preference_value", nullable = false, length = 64)
    public String preferenceValue;

    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamp(6) default current_timestamp(6)", insertable = false, updatable = false)
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    public LocalDateTime updatedAt;
}

