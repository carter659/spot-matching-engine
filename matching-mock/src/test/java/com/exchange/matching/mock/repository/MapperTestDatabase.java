package com.exchange.matching.mock.repository;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.exchange.matching.mock.mapper.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import javax.sql.DataSource;
import java.util.Map;

/** Real JPA schema generation and real MyBatis Plus statements on an isolated test database. */
public final class MapperTestDatabase {
    private final SqlSessionTemplate session;
    public MapperTestDatabase(DataSource source) {
        try {
            var jpa = new LocalContainerEntityManagerFactoryBean();
            jpa.setDataSource(source);
            jpa.setPackagesToScan("com.exchange.matching.mock.entity");
            jpa.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            jpa.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "update", "hibernate.hbm2ddl.halt_on_error", "true"));
            jpa.afterPropertiesSet();
            jpa.destroy();
            var configuration = new MybatisConfiguration();
            configuration.addMapper(CommandLogMapper.class);
            configuration.addMapper(ResultLogMapper.class);
            configuration.addMapper(PreferenceMapper.class);
            configuration.addMapper(TradingPairMapper.class);
            configuration.addMapper(BookStrategyMapper.class);
            configuration.addMapper(TradeStrategyMapper.class);
            configuration.addMapper(TradeReplayMapper.class);
            configuration.addMapper(ScenarioRunMapper.class);
            configuration.addMapper(StrategySnapshotMapper.class);
            configuration.addMapper(StrategyOrderMapper.class);
            var factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(source); factory.setConfiguration(configuration);
            session = new SqlSessionTemplate(factory.getObject());
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }
    public TradeStrategyRepository tradeStrategies() { return new TradeStrategyRepository(session.getMapper(TradeStrategyMapper.class),session.getMapper(TradeReplayMapper.class)); }
    public ScenarioRunMapper scenarios() { return session.getMapper(ScenarioRunMapper.class); }
    public MysqlMockStore store() { return new MysqlMockStore(session.getMapper(CommandLogMapper.class), session.getMapper(ResultLogMapper.class)); }
    public PreferenceRepository preferences() { return new PreferenceRepository(session.getMapper(PreferenceMapper.class)); }
    public TradingPairRepository pairs() { return new TradingPairRepository(session.getMapper(TradingPairMapper.class)); }
    public BookStrategyRepository strategies() { return new BookStrategyRepository(session.getMapper(BookStrategyMapper.class)); }
    public StrategyHistoryRepository strategyHistory() { return new StrategyHistoryRepository(session.getMapper(StrategySnapshotMapper.class), session.getMapper(StrategyOrderMapper.class)); }
}
