package vn.edu.hcmut.tmc.reporting.domain.model;

import java.time.Instant;
import java.util.UUID;

/** One accepted attendance event as handed to HR/Payroll. {@code attendanceId} is the receiver's dedupe key. */
public record PayrollRecord(UUID attendanceId, String employeeId, String factoryId, String gateId,
                            String direction, Instant occurredAt) {

    public PayrollRecord {
        if (attendanceId == null || employeeId == null || factoryId == null || gateId == null
                || direction == null || occurredAt == null) {
            throw new IllegalArgumentException("every payroll record field is required");
        }
    }
}
