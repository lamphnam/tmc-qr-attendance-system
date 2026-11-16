package vn.edu.hcmut.tmc.reporting.application.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Service;

import vn.edu.hcmut.tmc.reporting.domain.model.AttendanceReport;
import vn.edu.hcmut.tmc.reporting.domain.model.AttendanceReportQuery;
import vn.edu.hcmut.tmc.reporting.domain.model.DailyAttendanceRow;
import vn.edu.hcmut.tmc.reporting.domain.model.DashboardQuery;
import vn.edu.hcmut.tmc.reporting.domain.model.DataScope;
import vn.edu.hcmut.tmc.reporting.domain.model.FactoryCalendar;
import vn.edu.hcmut.tmc.reporting.domain.port.IAttendanceReportReader;
import vn.edu.hcmut.tmc.reporting.domain.port.IHrExportStatusReader;

/**
 * Read side (FR-4.1, FR-4.2). Depends on two read-only ports: it cannot create, claim or change an export batch.
 */
@Service
public class ReportQueryService {

    private final IAttendanceReportReader reader;
    private final IHrExportStatusReader exportStatus;
    private final DataScope scope;
    private final Clock clock;

    public ReportQueryService(IAttendanceReportReader reader, IHrExportStatusReader exportStatus, DataScope scope, Clock clock) {
        this.reader = reader;
        this.exportStatus = exportStatus;
        this.scope = scope;
        this.clock = clock;
    }

    /** @throws IllegalArgumentException for an invalid factory, range, department or page */
    public AttendanceReport attendanceReport(String factoryId, LocalDate from, LocalDate to, String department,
                                             int offset, int limit) {
        AttendanceReportQuery query = new AttendanceReportQuery(factoryId, from, to, department, offset, limit, scope);
        // One extra row tells whether another page exists without a second COUNT query.
        List<DailyAttendanceRow> rows = reader.dailyAttendance(query, limit + 1);
        boolean hasMore = rows.size() > limit;
        return new AttendanceReport(query, hasMore ? rows.subList(0, limit) : rows, hasMore,
                reader.departmentSummary(query));
    }

    /** @param date factory day; null means today in factory time */
    public Dashboard dashboard(String factoryId, LocalDate date) {
        LocalDate day = date != null ? date : FactoryCalendar.dateOf(clock.instant());
        return new Dashboard(reader.dashboard(new DashboardQuery(factoryId, day, scope)), exportStatus.latest().orElse(null));
    }
}
