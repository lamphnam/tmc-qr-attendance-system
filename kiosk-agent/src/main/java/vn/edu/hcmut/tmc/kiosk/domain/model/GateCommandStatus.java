package vn.edu.hcmut.tmc.kiosk.domain.model;

/**
 * Outcome of the open-gate COMMAND. {@code SENT} means the adapter's peer accepted the command; it is never
 * proof that a physical gate opened (a mock answering HTTP 200 proves even less).
 */
public enum GateCommandStatus {
    NOT_ATTEMPTED, SENT, FAILED, UNSUPPORTED
}
