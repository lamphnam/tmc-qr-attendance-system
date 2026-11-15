package vn.edu.hcmut.tmc.reporting.domain.model;

import java.util.List;

/** Page of daily rows plus the department totals of the whole range (not only of the page). */
public record AttendanceReport(AttendanceReportQuery query, List<DailyAttendanceRow> rows, boolean hasMore,
                               List<DepartmentSummary> departments) {

    public AttendanceReport {
        rows = List.copyOf(rows);
        departments = List.copyOf(departments);
    }
}
