package vn.edu.hcmut.tmc.kiosk.domain.port;

import vn.edu.hcmut.tmc.kiosk.domain.exception.WhitelistCacheException;
import vn.edu.hcmut.tmc.kiosk.domain.model.WhitelistSnapshot;

/** Snapshot update capability, separate from the local scan reader. No transport or secret codec is prescribed. */
public interface IWhitelistSnapshotStore {

    /**
     * Atomically replaces all employees AND metadata in this kiosk's configured scope. An empty snapshot clears
     * employees but persists its metadata. With no existing snapshot, any valid version (including zero) initializes
     * the cache. Otherwise only a strictly newer version is applied; equal/older versions leave all data unchanged.
     * Version comparison and replacement must be atomic under concurrent writers. A true result is returned only
     * after durable commit. Failure must leave the previous snapshot intact and must never fall back to plaintext
     * persistence. Does not change attendance events, used tokens or gate state, and never contacts central.
     *
     * @param snapshot non-null full snapshot, with metadata matching the configured kiosk/factory scope
     * @return true if committed, false if the version was not newer (not a successful refresh)
     * @throws IllegalArgumentException if snapshot is null or does not match the configured scope
     * @throws WhitelistCacheException if storage/material is unavailable or existing cache data is inconsistent
     */
    boolean replaceIfNewer(WhitelistSnapshot snapshot);
}
