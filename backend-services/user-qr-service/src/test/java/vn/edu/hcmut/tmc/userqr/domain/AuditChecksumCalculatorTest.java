package vn.edu.hcmut.tmc.userqr.domain;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import vn.edu.hcmut.tmc.userqr.domain.model.AuditEvent;
import vn.edu.hcmut.tmc.userqr.domain.service.AuditChecksumCalculator;

class AuditChecksumCalculatorTest {
    private final AuditChecksumCalculator calculator = new AuditChecksumCalculator();
    private final Instant timestamp = Instant.parse("2026-11-14T00:00:00.123Z");
    private final AuditEvent original = event("id", timestamp, "actor", "HR_STAFF", "REVOKE_QR", "EMP-001", "reason", "SUCCESS");

    @Test
    void detectsTamperingOfEveryEventFieldAndChainLink() {
        String hash = calculator.calculate(original, AuditChecksumCalculator.GENESIS_HASH);
        assertThat(hash).matches("[0-9a-f]{64}")
                .isEqualTo(calculator.calculate(original, AuditChecksumCalculator.GENESIS_HASH));
        List<AuditEvent> changed = List.of(
                event("other", timestamp, "actor", "HR_STAFF", "REVOKE_QR", "EMP-001", "reason", "SUCCESS"),
                event("id", timestamp.plusMillis(1), "actor", "HR_STAFF", "REVOKE_QR", "EMP-001", "reason", "SUCCESS"),
                event("id", timestamp, "other", "HR_STAFF", "REVOKE_QR", "EMP-001", "reason", "SUCCESS"),
                event("id", timestamp, "actor", "EMPLOYEE", "REVOKE_QR", "EMP-001", "reason", "SUCCESS"),
                event("id", timestamp, "actor", "HR_STAFF", "ACTIVATE_QR", "EMP-001", "reason", "SUCCESS"),
                event("id", timestamp, "actor", "HR_STAFF", "REVOKE_QR", "EMP-002", "reason", "SUCCESS"),
                event("id", timestamp, "actor", "HR_STAFF", "REVOKE_QR", "EMP-001", "edited", "SUCCESS"),
                event("id", timestamp, "actor", "HR_STAFF", "REVOKE_QR", "EMP-001", "reason", "FAILED"));
        for (AuditEvent value : changed) {
            assertThat(calculator.calculate(value, AuditChecksumCalculator.GENESIS_HASH)).isNotEqualTo(hash);
        }
        assertThat(calculator.calculate(original, "a".repeat(64))).isNotEqualTo(hash);
    }

    @Test
    void fieldBoundariesNullAndUnicodeAreUnambiguous() {
        AuditEvent first = event("id", timestamp, "ab", "c", "action", "target", null, "SUCCESS");
        AuditEvent second = event("id", timestamp, "a", "bc", "action", "target", "", "SUCCESS");
        assertThat(calculator.calculate(first, AuditChecksumCalculator.GENESIS_HASH))
                .isNotEqualTo(calculator.calculate(second, AuditChecksumCalculator.GENESIS_HASH));
        assertThat(calculator.calculate(event("id", timestamp, "a", "bc", "action", "target", "Lý do 🏭", "SUCCESS"),
                AuditChecksumCalculator.GENESIS_HASH)).matches("[0-9a-f]{64}");
    }

    private static AuditEvent event(String id, Instant time, String actor, String role, String action,
                                    String target, String payload, String status) {
        return new AuditEvent(id, time, actor, role, action, target, payload, status);
    }
}
