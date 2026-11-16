package vn.edu.hcmut.tmc.reporting.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Supplier;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import vn.edu.hcmut.tmc.reporting.domain.exception.ReportingStorageException;
import vn.edu.hcmut.tmc.reporting.domain.model.AttendanceReportQuery;
import vn.edu.hcmut.tmc.reporting.domain.model.DailyAttendanceRow;
import vn.edu.hcmut.tmc.reporting.domain.model.DashboardQuery;
import vn.edu.hcmut.tmc.reporting.domain.model.DashboardSnapshot;
import vn.edu.hcmut.tmc.reporting.domain.model.DepartmentSummary;
import vn.edu.hcmut.tmc.reporting.domain.model.FactoryCalendar;
import vn.edu.hcmut.tmc.reporting.domain.port.IAttendanceReportReader;

/**
 * Aggregations run in PostgreSQL (GROUP BY on the {@code (factory_id, occurred_at)} index range), so the service never
 * pulls raw events into memory. The factory day is computed with {@code AT TIME ZONE 'Asia/Ho_Chi_Minh'}.
 */
@Repository
public class PostgresAttendanceReportReader implements IAttendanceReportReader {

    /** Shared filter: one factory, a half-open UTC range, and the dev-fixture scope. */
    private static final String RANGE_FILTER =
            " e.factory_id = ? AND e.occurred_at >= ? AND e.occurred_at < ? AND (? OR NOT e.dev_fixture)\n";

    private static final String DAILY = """
            SELECT (e.occurred_at AT TIME ZONE ?)::date AS work_date,
                   e.employee_id, emp.full_name, emp.department,
                   MIN(e.occurred_at) FILTER (WHERE e.direction = 'IN')  AS first_in,
                   MAX(e.occurred_at) FILTER (WHERE e.direction = 'OUT') AS last_out,
                   COUNT(*) FILTER (WHERE e.direction = 'IN')  AS in_count,
                   COUNT(*) FILTER (WHERE e.direction = 'OUT') AS out_count
            FROM attendance_events e
            LEFT JOIN employees emp ON emp.employee_id = e.employee_id
            WHERE""" + RANGE_FILTER + """
              AND e.scan_result = 'ALLOWED'
              AND (CAST(? AS text) IS NULL OR emp.department = ?)
            GROUP BY 1, e.employee_id, emp.full_name, emp.department
            ORDER BY 1, e.employee_id
            LIMIT ? OFFSET ?
            """;

    private static final String DEPARTMENTS = """
            SELECT emp.department, COUNT(DISTINCT e.employee_id) AS employees, COUNT(*) AS scans
            FROM attendance_events e
            LEFT JOIN employees emp ON emp.employee_id = e.employee_id
            WHERE""" + RANGE_FILTER + """
              AND e.scan_result = 'ALLOWED'
              AND (CAST(? AS text) IS NULL OR emp.department = ?)
            GROUP BY emp.department
            ORDER BY emp.department NULLS LAST
            """;

    private static final String DAY_TOTALS = """
            SELECT COUNT(DISTINCT e.employee_id) FILTER (WHERE e.scan_result = 'ALLOWED' AND e.direction = 'IN') AS present,
                   COUNT(*) FILTER (WHERE e.scan_result = 'ALLOWED' AND e.direction = 'IN')  AS scans_in,
                   COUNT(*) FILTER (WHERE e.scan_result = 'ALLOWED' AND e.direction = 'OUT') AS scans_out,
                   COUNT(*) FILTER (WHERE e.scan_result = 'REJECTED') AS rejected,
                   MAX(e.occurred_at) AS last_scan
            FROM attendance_events e
            WHERE""" + RANGE_FILTER;

    private static final String STILL_INSIDE = """
            SELECT COUNT(*) FROM (
                SELECT DISTINCT ON (e.employee_id) e.direction
                FROM attendance_events e
                WHERE""" + RANGE_FILTER + """
                  AND e.scan_result = 'ALLOWED'
                ORDER BY e.employee_id, e.occurred_at DESC, e.attendance_id DESC
            ) last_scan
            WHERE last_scan.direction = 'IN'
            """;

    private static final String PRESENT_BY_DEPARTMENT = """
            SELECT emp.department, COUNT(DISTINCT e.employee_id) AS employees, COUNT(*) AS scans
            FROM attendance_events e
            LEFT JOIN employees emp ON emp.employee_id = e.employee_id
            WHERE""" + RANGE_FILTER + """
              AND e.scan_result = 'ALLOWED' AND e.direction = 'IN'
            GROUP BY emp.department
            ORDER BY emp.department NULLS LAST
            """;

    private final JdbcTemplate jdbc;

    public PostgresAttendanceReportReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<DailyAttendanceRow> dailyAttendance(AttendanceReportQuery q, int limit) {
        return guarded(() -> jdbc.query(DAILY, (rs, rowNum) -> new DailyAttendanceRow(
                        rs.getObject("work_date", java.time.LocalDate.class),
                        rs.getString("employee_id"),
                        rs.getString("full_name"),
                        rs.getString("department"),
                        instant(rs, "first_in"),
                        instant(rs, "last_out"),
                        rs.getInt("in_count"),
                        rs.getInt("out_count")),
                FactoryCalendar.ZONE.getId(),
                q.factoryId(), utc(FactoryCalendar.startOf(q.from())), utc(FactoryCalendar.endOf(q.to())),
                q.scope().includeDevFixtures(),
                q.department(), q.department(),
                limit, q.offset()));
    }

    @Override
    public List<DepartmentSummary> departmentSummary(AttendanceReportQuery q) {
        return guarded(() -> jdbc.query(DEPARTMENTS, PostgresAttendanceReportReader::mapDepartment,
                q.factoryId(), utc(FactoryCalendar.startOf(q.from())), utc(FactoryCalendar.endOf(q.to())),
                q.scope().includeDevFixtures(),
                q.department(), q.department()));
    }

    @Override
    public DashboardSnapshot dashboard(DashboardQuery q) {
        Object[] range = {q.factoryId(), utc(FactoryCalendar.startOf(q.date())), utc(FactoryCalendar.endOf(q.date())),
                q.scope().includeDevFixtures()};
        return guarded(() -> {
            DayTotals totals = jdbc.queryForObject(DAY_TOTALS, (rs, rowNum) -> new DayTotals(
                    rs.getInt("present"), rs.getInt("scans_in"), rs.getInt("scans_out"), rs.getInt("rejected"),
                    instant(rs, "last_scan")), range);
            Integer inside = jdbc.queryForObject(STILL_INSIDE, Integer.class, range);
            List<DepartmentSummary> byDepartment = jdbc.query(PRESENT_BY_DEPARTMENT,
                    PostgresAttendanceReportReader::mapDepartment, range);
            return new DashboardSnapshot(q.factoryId(), q.date(), totals.present(), totals.scansIn(), totals.scansOut(),
                    totals.rejected(), inside == null ? 0 : inside, totals.lastScan(), byDepartment);
        });
    }

    private record DayTotals(int present, int scansIn, int scansOut, int rejected, Instant lastScan) {
    }

    private static DepartmentSummary mapDepartment(ResultSet rs, int rowNum) throws SQLException {
        return new DepartmentSummary(rs.getString("department"), rs.getInt("employees"), rs.getInt("scans"));
    }

    private static <T> T guarded(Supplier<T> action) {
        try {
            return action.get();
        } catch (DataAccessException e) {
            throw new ReportingStorageException("central database unavailable", e);
        }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
