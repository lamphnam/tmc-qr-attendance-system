package vn.edu.hcmut.tmc.reporting.domain.model;

import java.time.LocalDate;

/** Factory-wide dashboard for one factory day (FR-4.2). */
public record DashboardQuery(String factoryId, LocalDate date, DataScope scope) {

    public DashboardQuery {
        if (!AttendanceReportQuery.isValidFactoryId(factoryId)) {
            throw new IllegalArgumentException("factoryId must be 1-16 letters, digits, '-' or '_'");
        }
        if (date == null || scope == null) {
            throw new IllegalArgumentException("date and scope are required");
        }
    }
}
