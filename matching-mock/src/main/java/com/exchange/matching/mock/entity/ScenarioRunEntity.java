package com.exchange.matching.mock.entity;
import jakarta.persistence.*;
import com.baomidou.mybatisplus.annotation.*;
@Entity @Table(name="mock_scenario_run") @TableName("mock_scenario_run")
public class ScenarioRunEntity {
 @Id @TableId(type=IdType.INPUT) @Column(length=36) public String id;
 public String symbol;
 public String scenario;
 public String status;
 public String created;
 @Column(columnDefinition="LONGTEXT") public String report;
}
