package com.exchange.matching.mock.repository;

import org.springframework.context.annotation.Profile;
import com.exchange.matching.mock.mapper.PreferenceMapper;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.stereotype.Repository;
import java.util.LinkedHashMap;
import java.util.Map;

@Repository
@Profile("database")
@DependsOnDatabaseInitialization
public class PreferenceRepository {
    private final PreferenceMapper mapper;
    public PreferenceRepository(PreferenceMapper mapper) { this.mapper = mapper; }
    public Map<String, String> read() {
        var values = new LinkedHashMap<String, String>();
        mapper.selectList(null).forEach(row -> values.put(row.preferenceKey, row.preferenceValue));
        return values;
    }
    public void save(String key, String value) {
        mapper.save(key, value);
    }
}
