package com.exchange.matching.server.controller;
import com.exchange.matching.server.config.AdminAuthFilter;
import com.exchange.matching.server.repository.AdminStore;
import jakarta.servlet.http.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AdminStore store;
    private int failures;
    private long blockedUntil;
    public AuthController(AdminStore store) { this.store=store; }
    @GetMapping("/session") public Map<String,Object> session(HttpServletRequest request) {
        var session = request.getSession(true);
        if (session.getAttribute("csrf") == null) session.setAttribute("csrf", UUID.randomUUID().toString());
        return Map.of("authenticated", AdminAuthFilter.authenticated(session, store), "csrfToken", session.getAttribute("csrf"), "username", "root");
    }
    public record Login(String username, String password) {}
    public record PasswordChange(String oldPassword, String newPassword) {}
    @PostMapping("/login") public synchronized ResponseEntity<?> login(@RequestBody Login login, HttpServletRequest request) {
        if (System.currentTimeMillis() < blockedUntil) return ResponseEntity.status(429).body(Map.of("error", "尝试过于频繁，请 30 秒后重试"));
        if (!store.authenticate(login.username(), login.password())) {
            if (++failures >= 10) { blockedUntil=System.currentTimeMillis()+30000; failures=0; }
            return ResponseEntity.status(401).body(Map.of("error", "用户名或密码错误"));
        }
        failures=0;
        request.changeSessionId();
        request.getSession().setAttribute("adminVersion",store.version());
        request.getSession().setAttribute("csrf",UUID.randomUUID().toString());
        return ResponseEntity.ok(session(request));
    }
    @PostMapping("/logout") public Map<String,Boolean> logout(HttpServletRequest request) { request.getSession().invalidate(); return Map.of("ok",true); }
    @PostMapping("/password") public ResponseEntity<?> password(@RequestBody PasswordChange change, HttpServletRequest request) {
        try {
            if (!store.changePassword(change.oldPassword(),change.newPassword())) return ResponseEntity.badRequest().body(Map.of("error","当前密码错误"));
            request.getSession().invalidate();
            return ResponseEntity.ok(Map.of("ok",true));
        } catch (IllegalArgumentException error) { return ResponseEntity.badRequest().body(Map.of("error",error.getMessage())); }
    }
}
