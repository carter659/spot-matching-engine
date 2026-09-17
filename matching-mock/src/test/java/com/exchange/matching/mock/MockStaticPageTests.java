package com.exchange.matching.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import java.net.URI;
import java.net.http.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class MockStaticPageTests {
    @LocalServerPort int port;
    @Test void servesTradingPageAndBrowserModulesWithoutExternalAssets() throws Exception {
        var client = HttpClient.newHttpClient();
        for (String path : new String[]{"/", "/trading.html", "/parameters.html", "/portal.css", "/portal-theme.js", "/parameters.js", "/trading.css", "/trading-live.css", "/trading.js", "/trading-model.mjs"}) {
            var response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), path);
            if (path.equals("/")) assertTrue(response.body().contains("/trading.html") && response.body().contains("/parameters.html"));
            if (path.equals("/trading.html")) assertTrue(response.body().contains("订单投递"));
            if (path.endsWith(".mjs")) assertTrue(response.headers().firstValue("content-type").orElse("")
                    .contains("javascript"));
        }
    }
}
