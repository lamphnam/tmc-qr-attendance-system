package vn.edu.hcmut.tmc.reporting.domain.model;

import java.time.Instant;
import java.util.UUID;

/** Raised when a batch becomes FAILED: HR/Admin must export by hand before the reconciliation deadline (NFR-6). */
public record ExportAlert(UUID batchUuid, ExportPeriod period, int attempts, String reason, Instant raisedAt) {
}
