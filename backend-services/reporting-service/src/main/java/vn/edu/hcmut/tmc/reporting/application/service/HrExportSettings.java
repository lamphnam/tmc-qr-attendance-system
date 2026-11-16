package vn.edu.hcmut.tmc.reporting.application.service;

import java.time.Duration;

/**
 * Operational knobs of the export use case.
 *
 * @param attemptLease how long one attempt may hold a batch before it is presumed crashed; must exceed the channel's
 *                     own timeout
 * @param dueBatchLimit how many due batches one poll handles
 */
public record HrExportSettings(Duration attemptLease, int dueBatchLimit) {

    public HrExportSettings {
        if (attemptLease == null || attemptLease.isNegative() || attemptLease.isZero() || dueBatchLimit < 1) {
            throw new IllegalArgumentException("attemptLease must be positive and dueBatchLimit >= 1");
        }
    }
}
