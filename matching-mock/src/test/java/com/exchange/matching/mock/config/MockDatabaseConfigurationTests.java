package com.exchange.matching.mock.config;

import com.exchange.matching.mock.repository.MysqlMockStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.*;

@ActiveProfiles("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.datasource.url=jdbc:h2:mem:mock-wiring;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.datasource.username=sa", "spring.datasource.password="
})
class MockDatabaseConfigurationTests {
    @Autowired MysqlMockStore store;
    @Test void initializesSchemaBeforeRepositoriesReadIt() throws Exception {
        assertTrue(store.commands().readAll().isEmpty());
        assertTrue(store.results().readAll().isEmpty());
    }
}
