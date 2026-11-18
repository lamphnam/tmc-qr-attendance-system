package vn.edu.hcmut.tmc.kiosk.domain.model;

import java.time.Instant;
import java.util.UUID;

/** Local audit record for a denied scan. It contains no QR credential or verification material. */
public record RejectedScan(UUID scanId, String employeeId, String gateId, Direction direction,
                           Instant occurredAt, Reason reason) {
    public enum Reason { UNKNOWN_EMPLOYEE, REVOKED, INVALID_TOTP, REPLAYED }
}
