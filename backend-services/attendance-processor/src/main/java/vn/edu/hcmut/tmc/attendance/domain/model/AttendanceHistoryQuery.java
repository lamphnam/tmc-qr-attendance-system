package vn.edu.hcmut.tmc.attendance.domain.model;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Internal query, not an HTTP contract. Time range is [fromInclusive, toExclusive), at B06 millisecond precision.
 * Page numbers are zero-based. Both employee and factory are mandatory; the caller must authorize the scope.
 */
public record AttendanceHistoryQuery(String employeeId, String factoryId, Instant fromInclusive, Instant toExclusive,
                                     int pageNumber, int pageSize) {

    public static final int MAX_PAGE_SIZE = 100;

    public AttendanceHistoryQuery {
        if (employeeId == null || employeeId.isBlank()) {
            throw new IllegalArgumentException("employeeId must not be blank");
        }
        employeeId = employeeId.trim();
        if (factoryId == null || factoryId.isBlank()) {
            throw new IllegalArgumentException("factoryId must not be blank");
        }
        factoryId = factoryId.trim();
        if (fromInclusive == null || toExclusive == null) {
            throw new IllegalArgumentException("both time bounds are required");
        }
        if (!fromInclusive.equals(fromInclusive.truncatedTo(ChronoUnit.MILLIS))
                || !toExclusive.equals(toExclusive.truncatedTo(ChronoUnit.MILLIS))) {
            // Reject rather than round a boundary and silently widen/narrow the requested range.
            throw new IllegalArgumentException("time bounds must have millisecond precision");
        }
        if (!fromInclusive.isBefore(toExclusive)) {
            throw new IllegalArgumentException("fromInclusive must precede toExclusive");
        }
        if (pageNumber < 0) {
            throw new IllegalArgumentException("pageNumber must not be negative");
        }
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("pageSize must be between 1 and " + MAX_PAGE_SIZE);
        }
    }

    public long offset() {
        return (long) pageNumber * pageSize;
    }
}
