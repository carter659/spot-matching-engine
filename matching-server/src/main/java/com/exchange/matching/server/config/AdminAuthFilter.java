package com.exchange.matching.server.config;
import com.exchange.matching.server.repository.AdminStore;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.Set;

@Component
public final class AdminAuthFilter extends OncePerRequestFilter {
    private final AdminStore store;
    public AdminAuthFilter(AdminStore store) { this.store = store; }
    public static boolean authenticated(HttpSession session, AdminStore store) {
        return session != null && Long.valueOf(store.version()).equals(session.getAttribute("adminVersion"));
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; object-src 'none'; base-uri 'self'; frame-ancestors 'none'");
        boolean publicPath = Set.of("/login.html", "/engine.css", "/admin.js", "/api/auth/session", "/api/auth/login").contains(path);
        var session = request.getSession(false);
        if (!publicPath && !authenticated(session, store)) {
            if (path.startsWith("/api/")) reject(response, 401, "请先登录");
            else response.sendRedirect(request.getContextPath() + "/login.html");
            return;
        }
        if (!Set.of("GET", "HEAD", "OPTIONS").contains(request.getMethod())) {
            Object token = session == null ? null : session.getAttribute("csrf");
            if (token == null || !token.equals(request.getHeader("X-CSRF-Token"))) { reject(response, 403, "会话校验失败，请刷新页面"); return; }
        }
        chain.doFilter(request, response);
    }
    private static void reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status); response.setContentType("application/json;charset=UTF-8"); response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
