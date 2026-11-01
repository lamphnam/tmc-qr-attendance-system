package vn.edu.hcmut.tmc.kiosk.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Typed view of the {@code kiosk.*} block in application.yml (every value comes from env vars in Compose). */
@ConfigurationProperties(prefix = "kiosk")
public record KioskProperties(
        String id,
        String factoryId,
        String defaultGateId,
        Sqlite sqlite,
        Central central,
        Turnstile turnstile,
        Sync sync) {

    public record Sqlite(String path) {
    }

    public record Central(String baseUrl, String syncPath, boolean disabled, int connectTimeoutMs, int readTimeoutMs) {
    }

    public record Turnstile(String baseUrl, int timeoutMs) {
    }

    public record Sync(boolean enabled, long intervalMs, int batchSize, long backoffBaseMs, long backoffMaxMs) {
    }
}
