package com.exchange.matching.server.controller;
import com.exchange.matching.server.config.AdminAuthFilter;
import com.exchange.matching.server.repository.AdminStore;
import com.exchange.matching.server.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.file.Path;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AdminAuthTests {
    @TempDir Path directory;
    AdminStore store;ReliableMatchingService service;MockMvc mvc;
    @BeforeEach void setup() throws Exception{
        store=new AdminStore(directory.resolve("db"));var metrics=new EngineMetrics();
        var queues=org.mockito.Mockito.mock(RabbitQueueMonitor.class);
        org.mockito.Mockito.when(queues.snapshot()).thenReturn(new com.exchange.matching.server.dto.RabbitQueueBacklog("commands.engine",true,2L,1L,3L,1L,"ok"));
        service=ReliableMatchingService.open(directory.resolve("commands"),store,metrics);
        mvc=MockMvcBuilders.standaloneSetup(new DashboardPreferencesController(store),new AuthController(store),new EngineController(service),new MetricsController(service,queues))
            .addFilters(new AdminAuthFilter(store)).build();
    }
    @AfterEach void close() throws Exception{service.close();store.close();}
    MockHttpSession session() throws Exception {return (MockHttpSession)mvc.perform(get("/api/auth/session")).andReturn().getRequest().getSession();}
    String csrf(MockHttpSession session){return (String)session.getAttribute("csrf");}
    void login(MockHttpSession session,String password,int expected) throws Exception {
        mvc.perform(post("/api/auth/login").session(session).header("X-CSRF-Token",csrf(session)).contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"root\",\"password\":\""+password+"\"}")).andExpect(status().is(expected));
    }
    @Test void preferencesRequireAuthenticationCsrfAndValidValues() throws Exception {
        String url="/api/admin/dashboard-preferences";
        mvc.perform(get(url)).andExpect(status().isUnauthorized());
        var first=session();login(first,"root",200);
        mvc.perform(post(url).session(first).contentType(MediaType.APPLICATION_JSON).content("{}" )).andExpect(status().isForbidden());
        mvc.perform(post(url).session(first).header("X-CSRF-Token",csrf(first)).contentType(MediaType.APPLICATION_JSON)
            .content("{\"theme\":\"light\",\"language\":\"en\"}")).andExpect(status().isOk());
        var second=session();login(second,"root",200);
        mvc.perform(get(url).session(second)).andExpect(status().isOk()).andExpect(jsonPath("$.theme").value("light")).andExpect(jsonPath("$.language").value("en"));
        mvc.perform(post(url).session(first).header("X-CSRF-Token",csrf(first)).contentType(MediaType.APPLICATION_JSON)
            .content("{\"theme\":\"light\",\"language\":\"bad\"}")).andExpect(status().isBadRequest());
        mvc.perform(post(url).session(first).header("X-CSRF-Token",csrf(first)).contentType(MediaType.APPLICATION_JSON)
            .content("{}" )).andExpect(status().isBadRequest());
    }
    @Test void protectsPagesAndApisAndRequiresCsrf() throws Exception{
        mvc.perform(get("/configuration.html")).andExpect(status().isFound()).andExpect(redirectedUrl("/login.html"));
        mvc.perform(get("/api/engine/metrics")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/engine/configuration").contentType(MediaType.APPLICATION_JSON).content("{}" )).andExpect(status().isUnauthorized());
        var session=session();login(session,"wrong",401);login(session,"root",200);
        mvc.perform(get("/api/engine/metrics").session(session)).andExpect(status().isOk())
            .andExpect(jsonPath("$.rabbitCommandQueue.total").value(3))
            .andExpect(jsonPath("$.rabbitCommandQueue.ready").value(2))
            .andExpect(jsonPath("$.rabbitCommandQueue.unacked").value(1));
        mvc.perform(post("/api/engine/configuration").session(session).contentType(MediaType.APPLICATION_JSON).content("{}" )).andExpect(status().isForbidden());
        mvc.perform(post("/api/engine/configuration").session(session).header("X-CSRF-Token",csrf(session)).contentType(MediaType.APPLICATION_JSON)
            .content("{\"symbols\":[\"custom/usdt\"],\"minOrderLots\":1,\"maxOrderLots\":9223372036854775807}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.parameters.symbols[0]").value("CUSTOM_USDT"));
    }
    @Test void metricsControllerReturnsLatestActualFillAndNullForNoTrade() throws Exception {
        var session=session();login(session,"root",200);
        mvc.perform(get("/api/engine/metrics").session(session)).andExpect(status().isOk())
            .andExpect(jsonPath("$.pairs[0].latestPriceTicks").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.pairs[0].latestQuantityLots").value(org.hamcrest.Matchers.nullValue()));
        service.process(new com.exchange.matching.protocol.command.OrderCommand("maker",com.exchange.matching.protocol.command.OrderCommand.Action.PLACE,
            1,"BTC_USDT",com.exchange.matching.protocol.command.OrderCommand.Side.SELL,12345,7));
        service.process(new com.exchange.matching.protocol.command.OrderCommand("taker",com.exchange.matching.protocol.command.OrderCommand.Action.PLACE,
            2,"BTC_USDT",com.exchange.matching.protocol.command.OrderCommand.Side.BUY,13000,3));
        mvc.perform(get("/api/engine/metrics").session(session)).andExpect(status().isOk())
            .andExpect(jsonPath("$.pairs[0].latestPriceTicks").value("12345"))
            .andExpect(jsonPath("$.pairs[0].latestQuantityLots").value("3"))
            .andExpect(jsonPath("$.pairs[0].bidOrderCount").value(0))
            .andExpect(jsonPath("$.pairs[0].bidLevelCount").value(0))
            .andExpect(jsonPath("$.pairs[0].askOrderCount").value(1))
            .andExpect(jsonPath("$.pairs[0].askLevelCount").value(1))
            .andExpect(jsonPath("$.counters.commands.total").value(2))
            .andExpect(jsonPath("$.counters.trades.total").value(1));
    }
    @Test void passwordChangeInvalidatesOtherSessionsAndOldPassword() throws Exception{
        var first=session();var second=session();login(first,"root",200);login(second,"root",200);
        mvc.perform(post("/api/auth/password").session(first).header("X-CSRF-Token",csrf(first)).contentType(MediaType.APPLICATION_JSON)
            .content("{\"oldPassword\":\"wrong\",\"newPassword\":\"new-secret\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/password").session(first).header("X-CSRF-Token",csrf(first)).contentType(MediaType.APPLICATION_JSON)
            .content("{\"oldPassword\":\"root\",\"newPassword\":\"new-secret\"}")).andExpect(status().isOk());
        mvc.perform(get("/api/engine/configuration").session(second)).andExpect(status().isUnauthorized());
        var third=session();login(third,"root",401);login(third,"new-secret",200);
        mvc.perform(post("/api/auth/logout").session(third).header("X-CSRF-Token",csrf(third))).andExpect(status().isOk());
        mvc.perform(get("/api/engine/metrics")).andExpect(status().isUnauthorized());
    }
}
