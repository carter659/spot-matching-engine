package com.exchange.matching.mock.controller;

import com.exchange.matching.mock.repository.MapperTestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PreferenceControllerTests {
    JdbcTemplate jdbc;
    MockMvc mvc;
    MapperTestDatabase database;
    @BeforeEach void setup() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        database = new MapperTestDatabase(source);
        jdbc = new JdbcTemplate(source);
        mvc = MockMvcBuilders.standaloneSetup(new PreferenceController(database.preferences())).build();
    }
    @Test void persistsAcrossRepositoryRecreationWithoutOverwritingOtherPreferences() throws Exception {
        mvc.perform(get("/mock/preferences")).andExpect(content().json("{}"));
        for (String body : new String[]{"{\"key\":\"theme\",\"value\":\"dark\"}",
                "{\"key\":\"symbol\",\"value\":\"SHIB_USDT\"}", "{\"key\":\"theme\",\"value\":\"light\"}"}) {
            mvc.perform(post("/mock/preferences").contentType("application/json").content(body)).andExpect(status().isOk());
        }
        var restored = database.preferences().read();
        assertEquals("light", restored.get("theme"));
        assertEquals("SHIB_USDT", restored.get("symbol"));
        assertEquals(2, restored.size());
    }
    @Test void rejectsUnknownKeysAndInvalidValuesWithoutWriting() throws Exception {
        for (String body : new String[]{"{}", "{\"key\":\"theme\",\"value\":\"bad\"}",
                "{\"key\":\"symbol\",\"value\":\"UNKNOWN\"}", "{\"key\":\"sql\",\"value\":\"abc\"}"}) {
            mvc.perform(post("/mock/preferences").contentType("application/json").content(body)).andExpect(status().isBadRequest());
        }
        assertTrue(database.preferences().read().isEmpty());
    }
    @Test void databaseFailureIsReportedInsteadOfClaimingSaved() throws Exception {
        jdbc.execute("DROP TABLE mock_preference");
        mvc.perform(post("/mock/preferences").contentType("application/json")
                .content("{\"key\":\"theme\",\"value\":\"dark\"}")).andExpect(status().isServiceUnavailable());
        mvc.perform(get("/mock/preferences")).andExpect(status().isServiceUnavailable());
    }
}
