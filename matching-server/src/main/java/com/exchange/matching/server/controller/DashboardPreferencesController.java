package com.exchange.matching.server.controller;
import com.exchange.matching.server.dto.DashboardPreferences;
import com.exchange.matching.server.repository.AdminStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController
@RequestMapping("/api/admin/dashboard-preferences")
public class DashboardPreferencesController {
    private final AdminStore store;
    public DashboardPreferencesController(AdminStore store) { this.store = store; }
    @GetMapping public DashboardPreferences read() { return store.dashboardPreferences(); }
    @PostMapping public ResponseEntity<?> save(@RequestBody DashboardPreferences preferences) {
        try { store.saveDashboardPreferences(preferences); return ResponseEntity.ok(preferences); }
        catch (IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }
}
