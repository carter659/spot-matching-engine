package com.exchange.matching.mock.repository;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class TradingPairRepositoryTests {
    @Test void initializesTwentyPairsAndPreservesSavedParametersOnRestart() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var database = new MapperTestDatabase(source);
        var jdbc = new JdbcTemplate(source);
        var repository = database.pairs();
        repository.initialize();
        assertEquals(20, repository.read().size());
        repository.update("BTC_USDT", "123.45");
        repository.update("SHIB_USDT", "0.00001234");
        var restored = new MapperTestDatabase(source).pairs();
        restored.initialize();
        assertEquals(20, restored.read().size());
        assertEquals("123.45", restored.read().getFirst().examplePrice());
        restored.delete("BTC_USDT");
        restored.initialize();
        assertEquals(19, restored.read().size());
        assertThrows(IllegalArgumentException.class, () -> restored.require("BTC_USDT", false));
        assertEquals("BTC_USDT", restored.require("BTC_USDT", true).symbol());
        var custom = new com.exchange.matching.protocol.model.TradingPair("SUI_USDT", "SUI", "USDT", 4, 4, "1.2345");
        restored.add(custom);
        assertEquals(custom, restored.require("SUI_USDT", false));
        assertThrows(IllegalArgumentException.class, () -> restored.add(custom));
        restored.update("SUI_USDT", "2.3456");
        assertEquals("2.3456", restored.require("SUI_USDT", false).examplePrice());
        restored.delete("SUI_USDT");
        restored.add(custom);
        restored.add(new com.exchange.matching.protocol.model.TradingPair("BTC_USDT", "BTC", "USDT", 2, 4, "123.45"));
        restored.delete("SUI_USDT");
        assertEquals("0.00001234", restored.read().getLast().examplePrice());
        assertThrows(IllegalArgumentException.class, () -> restored.update("BTC_USDT", "0.001"));
        assertThrows(IllegalArgumentException.class, () -> restored.update("BTC_USDT", "-1"));
        assertThrows(IllegalArgumentException.class, () -> restored.update("BTC_USDT", "99999999999999999999"));
        assertThrows(IllegalArgumentException.class, () -> restored.update("BAD_USDT", "1"));
        assertEquals("123.45", restored.read().getFirst().examplePrice());
        restored.update("BTC_USDT", "123.456789", 6, 8);
        assertEquals(6, restored.read().getFirst().priceScale());
        assertEquals(8, restored.read().getFirst().quantityScale());
        restored.initialize();
        assertEquals("123.456789", new MapperTestDatabase(source).pairs().require("BTC_USDT", false).examplePrice());
        assertThrows(IllegalArgumentException.class, () -> restored.update("BTC_USDT", "123.456789", 2, 8));
        assertThrows(IllegalArgumentException.class, () -> restored.update("BTC_USDT", "123", -1, 8));
        assertThrows(IllegalArgumentException.class, () -> restored.update("BTC_USDT", "123", 6, 9));
        restored.update("BTC_USDT", "123", 0, 0);
        assertEquals(0, restored.require("BTC_USDT", false).quantityScale());
        jdbc.update("INSERT INTO mock_command_log (command_id,order_id,symbol,action,order_type,time_in_force,price_ticks,quantity_lots,confirmed,payload) VALUES ('precision-test',1,'BTC_USDT','PLACE','LIMIT','GTC',123,1,false,'{}')");
        assertThrows(IllegalArgumentException.class, () -> restored.update("BTC_USDT", "123.00", 2, 4));
        restored.update("BTC_USDT", "124", 0, 0);
        assertEquals("124", restored.require("BTC_USDT", false).examplePrice());
        jdbc.update("UPDATE mock_trading_pair SET price_scale=9 WHERE symbol='BTC_USDT'");
        assertThrows(IllegalStateException.class, restored::read);
    }
}
