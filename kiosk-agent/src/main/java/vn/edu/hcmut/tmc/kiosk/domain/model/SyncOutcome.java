package vn.edu.hcmut.tmc.kiosk.domain.model;

/** Per-event answer from central (docs/API-CONTRACTS.md section 5). */
public enum SyncOutcome {
    /** Committed by this request - mark SYNCED. */
    STORED,
    /** Committed earlier (retry after a lost ack) - mark SYNCED. */
    ALREADY_EXISTS,
    /** Central could not persist right now - keep PENDING and retry. */
    RETRYABLE,
    /** Deterministic rejection - mark FAILED, never retry. */
    REJECTED_PERMANENT
}
