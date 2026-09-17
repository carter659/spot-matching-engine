package com.exchange.matching.server.dto;
public record DashboardPreferences(String theme, String language) {
    public void validate() {
        if (!("dark".equals(theme) || "light".equals(theme)) || !("zh-CN".equals(language) || "en".equals(language)))
            throw new IllegalArgumentException("Unsupported dashboard theme or language");
    }
}
