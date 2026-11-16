package vn.edu.hcmut.tmc.reporting.domain.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Counts for one factory day. {@code employeesPresent} = distinct employees with an accepted IN scan;
 * {@code stillInside} = employees whose last accepted scan of the day is IN (an estimate: a missed OUT scan counts as
 * inside). {@code rejectedScans} is 0 until rejected scans are synced to central (D06 is open).
 */
public record DashboardSnapshot(String factoryId, LocalDate date, int employeesPresent, int scansIn, int scansOut,
                                int rejectedScans, int stillInside, Instant lastScanAt,
                                List<DepartmentSummary> presentByDepartment) {

    public DashboardSnapshot {
        presentByDepartment = List.copyOf(presentByDepartment);
    }
}
