package vn.edu.hcmut.tmc.reporting.domain.model;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * State of one monthly export batch (ADR-03). Immutable: every transition returns a new value, and an illegal transition
 * throws, so the state machine lives here and nowhere else.
 *
 * <pre>
 *   PENDING --startAttempt--> SENDING --acknowledged--> ACKNOWLEDGED
 *      ^                         |------sent----------> SENT
 *      |------retryAt------------|------failed--------> FAILED --rearm--> PENDING
 * </pre>
 *
 * {@code attemptCount} doubles as the optimistic-lock version used by the store's compare-and-set.
 */
public record HrExportBatch(
        UUID batchUuid,
        ExportPeriod period,
        BatchStatus status,
        TriggerSource triggerSource,
        Instant createdAt,
        Instant retryDeadline,
        int attemptCount,
        Instant lastAttemptAt,
        Instant nextRetryAt,
        String lastError,
        int itemCount) {

    public HrExportBatch {
        if (batchUuid == null || period == null || status == null || triggerSource == null
                || createdAt == null || retryDeadline == null) {
            throw new IllegalArgumentException("batchUuid, period, status, triggerSource, createdAt and retryDeadline are required");
        }
        if (!batchUuid.equals(period.batchUuid())) {
            throw new IllegalArgumentException("batchUuid must be derived from the period (B24)");
        }
        if (attemptCount < 0 || itemCount < 0) {
            throw new IllegalArgumentException("counts must not be negative");
        }
    }

    /** A new batch, due immediately. */
    public static HrExportBatch newBatch(ExportPeriod period, TriggerSource source, Instant now, Instant retryDeadline) {
        return new HrExportBatch(period.batchUuid(), period, BatchStatus.PENDING, source, now, retryDeadline,
                0, null, now, null, 0);
    }

    /** Picked up by the background poll: waiting for a retry that is due, or holding a lease that has expired. */
    public boolean isDueAt(Instant now) {
        return (status == BatchStatus.PENDING || status == BatchStatus.SENDING)
                && nextRetryAt != null && !now.isBefore(nextRetryAt);
    }

    public boolean isLeaseHeldAt(Instant now) {
        return status == BatchStatus.SENDING && nextRetryAt != null && now.isBefore(nextRetryAt);
    }

    /** Claims the batch for one attempt; the lease bounds how long a crashed attempt can block the next one. */
    public HrExportBatch startAttempt(Instant now, Duration lease) {
        if (status != BatchStatus.PENDING && !(status == BatchStatus.SENDING && !isLeaseHeldAt(now))) {
            throw new IllegalStateException("cannot start an attempt from " + status);
        }
        return new HrExportBatch(batchUuid, period, BatchStatus.SENDING, triggerSource, createdAt, retryDeadline,
                attemptCount + 1, now, now.plus(lease), lastError, itemCount);
    }

    public HrExportBatch acknowledged() {
        return finish(BatchStatus.ACKNOWLEDGED, null, null);
    }

    public HrExportBatch sent() {
        return finish(BatchStatus.SENT, null, null);
    }

    public HrExportBatch retryAt(Instant next, String error) {
        if (next == null) {
            throw new IllegalArgumentException("next retry time is required");
        }
        return finish(BatchStatus.PENDING, next, error);
    }

    public HrExportBatch failed(String error) {
        return finish(BatchStatus.FAILED, null, error);
    }

    /** A person asked to try a FAILED batch again: same id, same frozen items, a fresh retry window. */
    public HrExportBatch rearm(Instant now, Instant newDeadline, TriggerSource source) {
        if (status != BatchStatus.FAILED) {
            throw new IllegalStateException("only a FAILED batch can be re-armed, not " + status);
        }
        return new HrExportBatch(batchUuid, period, BatchStatus.PENDING, source, createdAt, newDeadline,
                attemptCount, lastAttemptAt, now, lastError, itemCount);
    }

    private HrExportBatch finish(BatchStatus next, Instant nextRetry, String error) {
        if (status != BatchStatus.SENDING) {
            throw new IllegalStateException("an attempt can only finish from SENDING, not " + status);
        }
        return new HrExportBatch(batchUuid, period, next, triggerSource, createdAt, retryDeadline,
                attemptCount, lastAttemptAt, nextRetry, error, itemCount);
    }
}
