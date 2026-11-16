package vn.edu.hcmut.tmc.reporting.domain.port;

import java.util.List;

import vn.edu.hcmut.tmc.reporting.domain.model.AttendanceReportQuery;
import vn.edu.hcmut.tmc.reporting.domain.model.DailyAttendanceRow;
import vn.edu.hcmut.tmc.reporting.domain.model.DashboardQuery;
import vn.edu.hcmut.tmc.reporting.domain.model.DashboardSnapshot;
import vn.edu.hcmut.tmc.reporting.domain.model.DepartmentSummary;

/**
 * Read-only aggregation over {@code attendance_events} for reports and dashboards. Only accepted scans count as
 * attendance; rows outside {@code query.scope()} (dev fixtures) are excluded. Days are factory days. Throws
 * {@code ReportingStorageException} when the database is unreachable.
 */
public interface IAttendanceReportReader {

    /** Rows ordered by work date, then employee id; at most {@code limit} rows starting at {@code query.offset()}. */
    List<DailyAttendanceRow> dailyAttendance(AttendanceReportQuery query, int limit);

    /** Department totals over the whole range of {@code query} (ignores offset/limit). */
    List<DepartmentSummary> departmentSummary(AttendanceReportQuery query);

    DashboardSnapshot dashboard(DashboardQuery query);
}
