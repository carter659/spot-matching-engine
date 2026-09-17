package com.exchange.matching.server;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@org.springframework.test.context.ActiveProfiles("test")
class AppTests {
    @org.junit.jupiter.api.io.TempDir static java.nio.file.Path directory;
    @org.springframework.test.context.DynamicPropertySource
    static void properties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("matching.admin-data-directory", () -> directory.resolve("admin").toString());
    }

	@Test
	void contextLoads() {
	}

}
