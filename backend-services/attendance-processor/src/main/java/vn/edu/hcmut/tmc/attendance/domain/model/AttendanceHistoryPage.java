package vn.edu.hcmut.tmc.attendance.domain.model;

import java.util.List;

/** Immutable bounded page of raw attendance facts, not aggregated payroll/reporting data. */
public record AttendanceHistoryPage(List<AttendanceRecord> events, int pageNumber, int pageSize, boolean hasNext) {

    public AttendanceHistoryPage {
        if (events == null) {
            throw new IllegalArgumentException("events must not be null");
        }
        events = List.copyOf(events);
        if (pageNumber < 0 || pageSize < 1 || pageSize > AttendanceHistoryQuery.MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("invalid pageNumber or pageSize");
        }
        if (events.size() > pageSize || (hasNext && events.size() != pageSize)) {
            throw new IllegalArgumentException("event count must agree with pageSize and hasNext");
        }
    }
}
