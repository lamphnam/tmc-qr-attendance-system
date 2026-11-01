package vn.edu.hcmut.tmc.kiosk.domain.model;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * One row of the durable local queue. {@code attendanceId} is created ONCE at scan time and reused by every
 * retry, which is what lets central deduplicate (ADR-01). Timestamps are kept at millisecond precision so a
 * retried event is exactly the same fact on the wire.
 */
public record LocalAttendanceEvent(
        UUID attendanceId,
        String employeeId,
        String factoryId,
        String gateId,
        Direction direction,
        Instant occurredAt,
        ScanResult scanResult,
        SyncStatus syncStatus,
        GateCommandStatus gateCommandStatus,
        String gateCommandDetail,
        int retryCount,
        Instant nextRetryAt,
        String lastError,
        boolean devFixture,
        Instant createdAt,
        Instant syncedAt) {

    public LocalAttendanceEvent {
        occurredAt = ms(occurredAt);
        nextRetryAt = ms(nextRetryAt);
        createdAt = ms(createdAt);
        syncedAt = ms(syncedAt);
    }

    public static LocalAttendanceEvent newPending(UUID attendanceId, String employeeId, String factoryId, String gateId,
                                                  Direction direction, Instant occurredAt, ScanResult scanResult,
                                                  boolean devFixture, Instant now) {
        return new LocalAttendanceEvent(attendanceId, employeeId, factoryId, gateId, direction, occurredAt, scanResult,
                SyncStatus.PENDING, GateCommandStatus.NOT_ATTEMPTED, null, 0, null, null, devFixture, now, null);
    }

    private static Instant ms(Instant value) {
        return value == null ? null : value.truncatedTo(ChronoUnit.MILLIS);
    }
}
