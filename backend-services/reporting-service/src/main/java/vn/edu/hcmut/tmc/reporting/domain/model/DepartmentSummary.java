package vn.edu.hcmut.tmc.reporting.domain.model;

/** Team view of a report (FR-4.1 "team" = {@code employees.department}); {@code department} is null for unknown employees. */
public record DepartmentSummary(String department, int employees, int scans) {
}
