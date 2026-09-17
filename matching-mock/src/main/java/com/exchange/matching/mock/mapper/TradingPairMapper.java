package com.exchange.matching.mock.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exchange.matching.mock.entity.TradingPairEntity;

public interface TradingPairMapper extends BaseMapper<TradingPairEntity> {
    @org.apache.ibatis.annotations.Select("SELECT (SELECT COUNT(*) FROM mock_command_log WHERE symbol=#{symbol}) + (SELECT COUNT(*) FROM mock_result_log WHERE symbol=#{symbol})")
    long countHistory(@org.apache.ibatis.annotations.Param("symbol") String symbol);
}
