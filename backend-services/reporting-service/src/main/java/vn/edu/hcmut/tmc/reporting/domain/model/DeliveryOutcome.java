package vn.edu.hcmut.tmc.reporting.domain.model;

/**
 * What a payroll channel can report about one send. A closed set fixed by the {@code IHrPayrollGateway} contract:
 * adding a value is a contract change, so callers use an exhaustive {@code switch} expression over it.
 */
public enum DeliveryOutcome {
    /** The receiver itself confirmed it holds the batch. */
    ACKNOWLEDGED,
    /** Handed over completely, but the channel cannot know whether the receiver imported it (e.g. a file drop). */
    DELIVERED,
    /** Transient failure; resending the same batch later is safe and may succeed. */
    RETRYABLE,
    /** The receiver refused the content; resending the same batch cannot succeed. */
    REJECTED
}
