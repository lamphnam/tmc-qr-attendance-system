package vn.edu.hcmut.tmc.kiosk.domain.model;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Full local snapshot. An empty employee list is authoritative data, not a missing snapshot. */
public record WhitelistSnapshot(WhitelistMetadata metadata, List<WhitelistEmployee> employees) {

    public WhitelistSnapshot {
        if (metadata == null) {
            throw new IllegalArgumentException("metadata must not be null");
        }
        if (employees == null) {
            throw new IllegalArgumentException("employees must not be null");
        }
        Set<String> employeeIds = new HashSet<>();
        for (WhitelistEmployee employee : employees) {
            if (employee == null) {
                throw new IllegalArgumentException("employees must not contain null");
            }
            if (!employeeIds.add(employee.employeeId())) {
                throw new IllegalArgumentException("employees must have unique employeeId values");
            }
        }
        employees = List.copyOf(employees);
        if (metadata.employeeCount() != employees.size()) {
            throw new IllegalArgumentException("employeeCount must match the snapshot size");
        }
    }

    @Override
    public String toString() {
        return "WhitelistSnapshot[metadata=" + metadata + ", employeeCount=" + employees.size() + "]";
    }
}
