package vn.edu.hcmut.tmc.kiosk.domain.port;

import vn.edu.hcmut.tmc.kiosk.domain.exception.CentralUnavailableException;
import vn.edu.hcmut.tmc.kiosk.domain.model.WhitelistSnapshot;

/** Remote full-snapshot capability, independent of attendance delivery and of any HTTP representation. */
@FunctionalInterface
public interface IWhitelistSnapshotSource {

    /**
     * Fetches a complete snapshot for the trusted configured scope without reading or changing the local store.
     * Empty employees are authoritative data, not a missing/unavailable response. Implementations must validate
     * remote data before constructing domain models and must never return a partial snapshot or fake success.
     * Material remains sensitive byte arrays; no endpoint, JSON shape or secret encoding is defined by this port.
     *
     * <p>The returned metadata identifies the requested kiosk/factory and a non-negative source version.
     * Its cachedAt is provisional: the application assigns the local Clock time after retrieval, before storage.
     * No TTL, expiry or rotation policy is imposed. A future transport adapter must bound its own network waits.
     *
     * @param kioskId non-null, non-blank normalized kiosk id from trusted configuration
     * @param factoryId non-null, non-blank normalized factory id from trusted configuration
     * @return a non-null immutable full snapshot, including a valid empty snapshot
     * @throws CentralUnavailableException if the snapshot cannot be obtained (including timeout/unavailable Central);
     *                                     diagnostics must not contain secrets or response bodies
     * @throws IllegalArgumentException if input or retrieved snapshot data is invalid;
     *                                  diagnostics must not contain secrets or response bodies
     */
    WhitelistSnapshot fetchSnapshot(String kioskId, String factoryId);
}
