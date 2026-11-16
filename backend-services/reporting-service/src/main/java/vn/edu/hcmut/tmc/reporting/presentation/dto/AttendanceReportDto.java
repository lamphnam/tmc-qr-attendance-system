package vn.edu.hcmut.tmc.reporting.presentation.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import vn.edu.hcmut.tmc.reporting.domain.model.AttendanceReport;
import vn.edu.hcmut.tmc.reporting.domain.model.DailyAttendanceRow;
import vn.edu.hcmut.tmc.reporting.domain.model.DepartmentSummary;
import vn.edu.hcmut.tmc.reporting.domain.model.FactoryCalendar;

/** FR-4.1 response. Dates are factory days in {@code timeZone}; instants are UTC. */
public record AttendanceReportDto(String factoryId, LocalDate from, LocalDate to, String department, String timeZone,
                                  int offset, int limit, boolean hasMore, List<Row> rows, List<Department> departments) {

    public record Row(LocalDate workDate, String employeeId, String fullName, String department, Instant firstIn,
                      Instant lastOut, int inCount, int outCount) {

        static Row from(DailyAttendanceRow r) {
            return new Row(r.workDate(), r.employeeId(), r.fullName(), r.department(), r.firstIn(), r.lastOut(),
                    r.inCount(), r.outCount());
        }
    }

    public record Department(String department, int employees, int scans) {

        static Department from(DepartmentSummary d) {
            return new Department(d.department(), d.employees(), d.scans());
        }
    }

    public static AttendanceReportDto from(AttendanceReport report) {
        var q = report.query();
        return new AttendanceReportDto(q.factoryId(), q.from(), q.to(), q.department(), FactoryCalendar.ZONE.getId(),
                q.offset(), q.limit(), report.hasMore(),
                report.rows().stream().map(Row::from).toList(),
                report.departments().stream().map(Department::from).toList());
    }
}
