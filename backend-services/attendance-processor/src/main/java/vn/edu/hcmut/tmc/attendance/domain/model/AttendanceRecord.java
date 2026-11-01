package vn.edu.hcmut.tmc.attendance.domain.model;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/**
 * One immutable attendance fact produced at the edge. {@code attendanceId} is generated once, at scan
 * time, by the kiosk and is the idempotency key (ADR-01).
 *
 * <p>{@code occurredAt} is truncated to milliseconds so that a retried event compares equal to the
 * stored one regardless of sub-millisecond precision lost on the wire or in PostgreSQL.
 *
 * @param sourceKioskId nullable - only known when the event arrived inside a batch
 * @param devFixture    true when the row came through a dev-only fixture endpoint
 */
public record AttendanceRecord(
        UUID attendanceId,
        String employeeId,
        String factoryId,
        String gateId,
        Direction direction,
        Instant occurredAt,
        ScanResult scanResult,
        String sourceKioskId,
        boolean devFixture) {

    public AttendanceRecord {
        if (attendanceId == null) {
            throw new IllegalArgumentException("attendanceId must not be null");
        }
        employeeId = requireText(employeeId, "employeeId");
        factoryId = requireText(factoryId, "factoryId");
        gateId = requireText(gateId, "gateId");
        if (direction == null) {
            throw new IllegalArgumentException("direction must not be null");
        }
        if (occurredAt == null) {
            throw new IllegalArgumentException("occurredAt must not be null");
        }
        occurredAt = occurredAt.truncatedTo(ChronoUnit.MILLIS);
        if (scanResult == null) {
            throw new IllegalArgumentException("scanResult must not be null");
        }
    }

    /** Business fields only; {@code sourceKioskId} / {@code devFixture} are metadata, not part of the fact. */
    public boolean samePayloadAs(AttendanceRecord other) {
        return other != null
                && attendanceId.equals(other.attendanceId)
                && employeeId.equals(other.employeeId)
                && factoryId.equals(other.factoryId)
                && gateId.equals(other.gateId)
                && direction == other.direction
                && occurredAt.equals(other.occurredAt)
                && scanResult == other.scanResult;
    }

    private static String requireText(String value, String field) {
        if (Objects.isNull(value) || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
