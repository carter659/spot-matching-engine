package com.exchange.matching.mock.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exchange.matching.mock.entity.PreferenceEntity;

public interface PreferenceMapper extends BaseMapper<PreferenceEntity> {
    @org.apache.ibatis.annotations.Insert("INSERT INTO mock_preference (preference_key, preference_value) VALUES (#{key}, #{value}) "
            + "ON DUPLICATE KEY UPDATE preference_value = #{value}, updated_at = CURRENT_TIMESTAMP(6)")
    int save(@org.apache.ibatis.annotations.Param("key") String key, @org.apache.ibatis.annotations.Param("value") String value);
}

