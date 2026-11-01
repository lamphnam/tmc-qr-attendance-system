package vn.edu.hcmut.tmc.kiosk.domain.model;

/** CENTRAL synchronisation state of a local event. It says nothing about the gate physically opening. */
public enum SyncStatus {
    PENDING, SYNCED, FAILED
}
