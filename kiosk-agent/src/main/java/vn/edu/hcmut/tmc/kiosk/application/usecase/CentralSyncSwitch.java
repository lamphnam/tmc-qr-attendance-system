package vn.edu.hcmut.tmc.kiosk.application.usecase;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runtime switch that disables ONLY kiosk -> central synchronisation (decision D05). Scans, the SQLite queue and the
 * local turnstile keep working while it is off, which is exactly the offline situation the demo needs to show.
 * Stopping the central containers is a different (and also valid) failure, but it is not a WAN-isolation simulation.
 */
public class CentralSyncSwitch {

    private final AtomicBoolean disabled;

    public CentralSyncSwitch(boolean initiallyDisabled) {
        this.disabled = new AtomicBoolean(initiallyDisabled);
    }

    public boolean isDisabled() {
        return disabled.get();
    }

    public void setDisabled(boolean value) {
        disabled.set(value);
    }
}
