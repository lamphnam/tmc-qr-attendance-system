package vn.edu.hcmut.tmc.userqr.domain.model;

import java.util.List;

/** One factory revision and its complete employee view, including an empty view. */
public record WhitelistSnapshot(long version, List<EmployeeQr> employees) {
    public WhitelistSnapshot {
        if (version < 1) throw new IllegalArgumentException("Whitelist revision must be positive");
        employees = List.copyOf(employees);
    }
}
