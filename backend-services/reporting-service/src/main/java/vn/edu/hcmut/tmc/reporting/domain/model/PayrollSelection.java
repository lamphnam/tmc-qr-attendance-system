package vn.edu.hcmut.tmc.reporting.domain.model;

/**
 * Which attendance rows belong in a period's batch (B24): {@code scan_result = 'ALLOWED'}, {@code occurred_at} in
 * {@code [period.startInclusive(), period.endExclusive())}, and no dev fixtures unless {@code scope} allows them.
 * Rejected scans are never sent to payroll.
 */
public record PayrollSelection(ExportPeriod period, DataScope scope) {

    public PayrollSelection {
        if (period == null || scope == null) {
            throw new IllegalArgumentException("period and scope are required");
        }
    }
}
