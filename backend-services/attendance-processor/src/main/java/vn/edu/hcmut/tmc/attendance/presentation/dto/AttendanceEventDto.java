package vn.edu.hcmut.tmc.attendance.presentation.dto;

import java.time.DateTimeException;
import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;

import vn.edu.hcmut.tmc.attendance.domain.model.AttendanceRecord;
import vn.edu.hcmut.tmc.attendance.domain.model.Direction;
import vn.edu.hcmut.tmc.attendance.domain.model.ScanResult;

/**
 * [DRAFT] wire shape from docs/API-CONTRACTS.md section 4/5. Fields are Strings on purpose: parsing happens in
 * {@link #toDomain} so a malformed value yields a clear per-event rejection instead of a framework error.
 */
public record AttendanceEventDto(
        @NotBlank String attendanceId,
        @NotBlank String employeeId,
        @NotBlank String factoryId,
        @NotBlank String gateId,
        @NotBlank String direction,
        @NotBlank String occurredAt,
        @NotBlank String scanResult) {

    public static AttendanceEventDto from(AttendanceRecord record) {
        return new AttendanceEventDto(
                record.attendanceId().toString(),
                record.employeeId(),
                record.factoryId(),
                record.gateId(),
                record.direction().name(),
                record.occurredAt().toString(),
                record.scanResult().name());
    }

    /** @throws IllegalArgumentException if any field is missing or malformed */
    public AttendanceRecord toDomain(String sourceKioskId, boolean devFixture) {
        return new AttendanceRecord(
                parseUuid(attendanceId),
                employeeId,
                factoryId,
                gateId,
                parseEnum(Direction.class, direction, "direction"),
                parseInstant(occurredAt),
                parseEnum(ScanResult.class, scanResult, "scanResult"),
                sourceKioskId,
                devFixture);
    }

    private static UUID parseUuid(String value) {
        if (value == null) {
            throw new IllegalArgumentException("attendanceId must not be null");
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("attendanceId is not a valid UUID");
        }
    }

    private static Instant parseInstant(String value) {
        if (value == null) {
            throw new IllegalArgumentException("occurredAt must not be null");
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("occurredAt must be an ISO-8601 UTC instant, e.g. 2026-11-01T01:20:00Z");
        }
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(field + " must be one of " + java.util.Arrays.toString(type.getEnumConstants()));
        }
    }
}
