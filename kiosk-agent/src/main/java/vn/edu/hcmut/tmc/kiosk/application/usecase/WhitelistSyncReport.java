package vn.edu.hcmut.tmc.kiosk.application.usecase;

import java.util.Objects;
import java.util.OptionalLong;

/** One whitelist pass; carries no employee data, material or untrusted exception diagnostics. */
public record WhitelistSyncReport(Status status, OptionalLong snapshotVersion) {

    public enum Status {
        UPDATED, NOT_NEWER, OFFLINE, ALREADY_RUNNING,
        SOURCE_UNAVAILABLE, INVALID_SNAPSHOT, CACHE_UNAVAILABLE, INVALID_CACHE
    }

    public WhitelistSyncReport {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(snapshotVersion, "snapshotVersion");
        boolean versioned = status == Status.UPDATED || status == Status.NOT_NEWER;
        if (versioned != snapshotVersion.isPresent()
                || (snapshotVersion.isPresent() && snapshotVersion.getAsLong() < 0)) {
            throw new IllegalArgumentException("version must be non-negative and present only for a versioned result");
        }
    }

    public static WhitelistSyncReport withoutSnapshot(Status status) {
        return new WhitelistSyncReport(status, OptionalLong.empty());
    }

    /** For NOT_NEWER, version describes the fetched candidate, not necessarily the current stored version. */
    public static WhitelistSyncReport forSnapshot(Status status, long version) {
        return new WhitelistSyncReport(status, OptionalLong.of(version));
    }
}
