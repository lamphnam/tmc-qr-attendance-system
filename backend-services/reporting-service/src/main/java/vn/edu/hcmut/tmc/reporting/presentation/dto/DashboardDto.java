package vn.edu.hcmut.tmc.reporting.presentation.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import vn.edu.hcmut.tmc.reporting.application.service.Dashboard;
import vn.edu.hcmut.tmc.reporting.domain.model.DashboardSnapshot;
import vn.edu.hcmut.tmc.reporting.domain.model.FactoryCalendar;

/** FR-4.2 response: one factory day plus the latest HR export batch (null when none exists). */
public record DashboardDto(String factoryId, LocalDate date, String timeZone, int employeesPresent, int scansIn,
                           int scansOut, int rejectedScans, int stillInside, Instant lastScanAt,
                           List<AttendanceReportDto.Department> presentByDepartment, HrExportBatchDto latestHrExport) {

    public static DashboardDto from(Dashboard dashboard) {
        DashboardSnapshot s = dashboard.attendance();
        return new DashboardDto(s.factoryId(), s.date(), FactoryCalendar.ZONE.getId(), s.employeesPresent(), s.scansIn(),
                s.scansOut(), s.rejectedScans(), s.stillInside(), s.lastScanAt(),
                s.presentByDepartment().stream().map(AttendanceReportDto.Department::from).toList(),
                HrExportBatchDto.from(dashboard.latestExport()));
    }
}
