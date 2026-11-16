package vn.edu.hcmut.tmc.kiosk.application.usecase;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import vn.edu.hcmut.tmc.kiosk.application.usecase.WhitelistSyncReport.Status;
import vn.edu.hcmut.tmc.kiosk.domain.exception.CentralUnavailableException;
import vn.edu.hcmut.tmc.kiosk.domain.exception.WhitelistCacheException;
import vn.edu.hcmut.tmc.kiosk.domain.model.KioskIdentity;
import vn.edu.hcmut.tmc.kiosk.domain.model.WhitelistMetadata;
import vn.edu.hcmut.tmc.kiosk.domain.model.WhitelistSnapshot;
import vn.edu.hcmut.tmc.kiosk.domain.port.IWhitelistSnapshotSource;
import vn.edu.hcmut.tmc.kiosk.domain.port.IWhitelistSnapshotStore;

/**
 * Downloads before entering storage; only the store owns transactions and atomic version comparison.
 * Compose one instance per kiosk with the existing Clock, identity and CentralSyncSwitch when a real source exists.
 * No automatic bean, HTTP client or scheduler is supplied while that source is unimplemented.
 */
public final class SyncWhitelistUseCase {

    private final IWhitelistSnapshotSource source;
    private final IWhitelistSnapshotStore store;
    private final CentralSyncSwitch centralSync;
    private final String kioskId;
    private final String factoryId;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean();

    public SyncWhitelistUseCase(IWhitelistSnapshotSource source, IWhitelistSnapshotStore store,
            CentralSyncSwitch centralSync, KioskIdentity identity, Clock clock) {
        this.source = Objects.requireNonNull(source, "source");
        this.store = Objects.requireNonNull(store, "store");
        this.centralSync = Objects.requireNonNull(centralSync, "centralSync");
        Objects.requireNonNull(identity, "identity");
        this.kioskId = requireId(identity.kioskId());
        this.factoryId = requireId(identity.factoryId());
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Returns UPDATED only after replaceIfNewer returns true (durable commit), NOT_NEWER is not a refresh.
     * The non-blocking guard covers fetch through commit, including recursive calls, and is released on all exits.
     * Offline is sampled before fetching and again before storage; it does not cancel an already-started call.
     * Expected port failures become safe reports. Unexpected programming failures propagate without being logged.
     */
    public WhitelistSyncReport run() {
        if (centralSync.isDisabled()) {
            return WhitelistSyncReport.withoutSnapshot(Status.OFFLINE);
        }
        if (!running.compareAndSet(false, true)) {
            return WhitelistSyncReport.withoutSnapshot(Status.ALREADY_RUNNING);
        }
        try {
            if (centralSync.isDisabled()) {
                return WhitelistSyncReport.withoutSnapshot(Status.OFFLINE);
            }
            return syncSnapshot();
        } finally {
            running.set(false);
        }
    }

    private WhitelistSyncReport syncSnapshot() {
        WhitelistSnapshot fetched;
        try {
            fetched = source.fetchSnapshot(kioskId, factoryId);
        } catch (CentralUnavailableException unavailable) {
            return WhitelistSyncReport.withoutSnapshot(Status.SOURCE_UNAVAILABLE);
        } catch (IllegalArgumentException invalidData) {
            return WhitelistSyncReport.withoutSnapshot(Status.INVALID_SNAPSHOT);
        }
        if (fetched == null || !matchesScope(fetched.metadata())) {
            return WhitelistSyncReport.withoutSnapshot(Status.INVALID_SNAPSHOT);
        }
        if (centralSync.isDisabled()) {
            return WhitelistSyncReport.withoutSnapshot(Status.OFFLINE);
        }

        WhitelistMetadata metadata = fetched.metadata();
        WhitelistSnapshot local = new WhitelistSnapshot(new WhitelistMetadata(kioskId, factoryId,
                metadata.version(), clock.instant(), metadata.employeeCount()), fetched.employees());
        try {
            // A pre-read would race another writer; the existing store compares versions inside its write lock.
            boolean committed = store.replaceIfNewer(local);
            return WhitelistSyncReport.forSnapshot(committed ? Status.UPDATED : Status.NOT_NEWER, metadata.version());
        } catch (WhitelistCacheException failure) {
            return WhitelistSyncReport.withoutSnapshot(switch (failure.reason()) {
                case UNAVAILABLE -> Status.CACHE_UNAVAILABLE;
                case INVALID_CACHE -> Status.INVALID_CACHE;
            });
        } catch (IllegalArgumentException invalidData) {
            return WhitelistSyncReport.withoutSnapshot(Status.INVALID_SNAPSHOT);
        }
    }

    private boolean matchesScope(WhitelistMetadata metadata) {
        return kioskId.equals(metadata.kioskId()) && factoryId.equals(metadata.factoryId());
    }

    private static String requireId(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("configured kiosk and factory ids must not be blank");
        }
        return value.strip();
    }
}
