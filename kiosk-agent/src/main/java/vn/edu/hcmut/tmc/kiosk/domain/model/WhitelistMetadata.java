package vn.edu.hcmut.tmc.kiosk.domain.model;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** Metadata of an initialized local snapshot, including a valid snapshot with zero employees. */
public record WhitelistMetadata(String kioskId, String factoryId, long version, Instant cachedAt, int employeeCount) {

    public WhitelistMetadata {
        if (kioskId == null || kioskId.isBlank()) {
            throw new IllegalArgumentException("kioskId must not be blank");
        }
        kioskId = kioskId.strip();
        if (factoryId == null || factoryId.isBlank()) {
            throw new IllegalArgumentException("factoryId must not be blank");
        }
        factoryId = factoryId.strip();
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        if (cachedAt == null) {
            throw new IllegalArgumentException("cachedAt must not be null");
        }
        cachedAt = cachedAt.truncatedTo(ChronoUnit.MILLIS);
        if (employeeCount < 0) {
            throw new IllegalArgumentException("employeeCount must not be negative");
        }
    }
}
