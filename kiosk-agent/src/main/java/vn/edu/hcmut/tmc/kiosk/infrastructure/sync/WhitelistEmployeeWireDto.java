package vn.edu.hcmut.tmc.kiosk.infrastructure.sync;

import java.time.Instant;

/** Current Central employee representation only; sensitive transport data, not a domain/cache snapshot. */
public record WhitelistEmployeeWireDto(String employeeId, String fullName, String factoryId, String department,
        String sharedSecret, String qrStatus, int secretVersion, Instant updatedAt) {

    public WhitelistEmployeeWireDto {
        if (employeeId == null || employeeId.isBlank() || fullName == null || factoryId == null
                || factoryId.isBlank() || department == null || sharedSecret == null || sharedSecret.isBlank()
                || !("ACTIVE".equals(qrStatus) || "REVOKED".equals(qrStatus))
                || secretVersion < 1 || updatedAt == null) {
            throw new IllegalArgumentException("invalid whitelist employee response");
        }
    }

    @Override
    public String toString() {
        return "WhitelistEmployeeWireDto[<redacted>]";
    }
}
