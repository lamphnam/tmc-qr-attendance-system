package vn.edu.hcmut.tmc.reporting.domain.model;

/** Lifecycle of an HR export batch; the same values as the CHECK constraint on {@code hr_export_batches.status}. */
public enum BatchStatus {
    /** Waiting for its first attempt or for the next retry at {@code nextRetryAt}. */
    PENDING,
    /** An attempt holds a lease until {@code nextRetryAt}; after that the attempt is presumed crashed and retried. */
    SENDING,
    /** Handed over to the receiver, which gives no confirmation (file drop). Not retried. */
    SENT,
    /** The receiver confirmed the batch. Not retried. */
    ACKNOWLEDGED,
    /** Gave up (deadline passed or the receiver rejected it); an alert was raised and a person must act. */
    FAILED;

    public boolean isDelivered() {
        return this == SENT || this == ACKNOWLEDGED;
    }
}
