package vn.edu.hcmut.tmc.attendance.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import vn.edu.hcmut.tmc.attendance.domain.model.AttendanceRecord;
import vn.edu.hcmut.tmc.attendance.domain.model.Direction;
import vn.edu.hcmut.tmc.attendance.domain.model.ScanResult;
import vn.edu.hcmut.tmc.attendance.domain.service.IdempotencyChecker;

class AttendanceDomainTest {

    private static final UUID ID = UUID.fromString("c5b0ff1a-2c59-4333-ac72-1b41b84e84a1");

    static AttendanceRecord record(String employeeId, Instant at) {
        return new AttendanceRecord(ID, employeeId, "BD", "BD-G01", Direction.IN, at, ScanResult.ALLOWED, null, true);
    }

    @Test
    void blankEmployeeIdIsRejected() {
        assertThatThrownBy(() -> record("  ", Instant.parse("2026-11-01T01:20:00Z")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("employeeId");
    }

    @Test
    void occurredAtIsTruncatedToMillisSoRetriesCompareEqual() {
        AttendanceRecord stored = record("EMP001", Instant.parse("2026-11-01T01:20:00.123Z"));
        AttendanceRecord retried = record("EMP001", Instant.parse("2026-11-01T01:20:00.123456789Z"));

        assertThat(retried.occurredAt()).isEqualTo(stored.occurredAt());
        assertThat(new IdempotencyChecker().compare(stored, retried)).isEqualTo(IdempotencyChecker.Verdict.SAME_PAYLOAD);
    }

    @Test
    void samePayloadIgnoresMetadataButDifferentFactIsAConflict() {
        Instant at = Instant.parse("2026-11-01T01:20:00Z");
        AttendanceRecord existing = record("EMP001", at);
        AttendanceRecord otherMetadata = new AttendanceRecord(ID, "EMP001", "BD", "BD-G01", Direction.IN, at,
                ScanResult.ALLOWED, "BD-K01", false);
        AttendanceRecord differentFact = record("EMP002", at);

        IdempotencyChecker checker = new IdempotencyChecker();
        assertThat(checker.compare(existing, otherMetadata)).isEqualTo(IdempotencyChecker.Verdict.SAME_PAYLOAD);
        assertThat(checker.compare(existing, differentFact)).isEqualTo(IdempotencyChecker.Verdict.CONFLICT);
    }
}
