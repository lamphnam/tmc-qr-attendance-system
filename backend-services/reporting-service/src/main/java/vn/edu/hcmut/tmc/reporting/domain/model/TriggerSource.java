package vn.edu.hcmut.tmc.reporting.domain.model;

/** Who created or last re-armed a batch. Recorded so a reviewer can see that a manual trigger reused a scheduled batch. */
public enum TriggerSource {
    SCHEDULED,
    MANUAL
}
