package vn.edu.hcmut.tmc.kiosk.infrastructure.sync;

import java.time.Clock;
import java.util.List;
import java.util.Objects;

import vn.edu.hcmut.tmc.kiosk.domain.exception.CentralUnavailableException;
import vn.edu.hcmut.tmc.kiosk.domain.model.KioskIdentity;
import vn.edu.hcmut.tmc.kiosk.domain.model.WhitelistEmployee;
import vn.edu.hcmut.tmc.kiosk.domain.model.WhitelistMetadata;
import vn.edu.hcmut.tmc.kiosk.domain.model.WhitelistSnapshot;
import vn.edu.hcmut.tmc.kiosk.domain.port.IWhitelistSnapshotSource;

/**
 * HTTP source registered only by opt-in configuration. It neither reads nor changes the cache.
 * Local identity binds the requested filter, not authorization: the dev credential is not factory-bound.
 * Central must return a complete factory snapshot and its revision from the same database snapshot.
 */
public final class RestWhitelistSnapshotSource implements IWhitelistSnapshotSource {

    private final WhitelistHttpTransport transport;
    private final WhitelistEmployeeMapper mapper;
    private final Clock clock;
    private final String kioskId;
    private final String factoryId;

    public RestWhitelistSnapshotSource(WhitelistHttpTransport transport, WhitelistEmployeeMapper mapper,
            KioskIdentity identity, Clock clock) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.clock = Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(identity, "identity");
        this.kioskId = configuredId(identity.kioskId());
        this.factoryId = configuredId(identity.factoryId());
    }

    /**
     * Requires the configured kiosk/factory exactly. Positive long revisions come only from X-Whitelist-Version;
     * repeated/comma-combined values are accepted only when every value represents the same positive long.
     * A valid empty array uses the configured request scope under Central's full-snapshot contract, not row inference.
     * cachedAt is provisional Clock time; the application assigns the persistence timestamp later.
     * Offline checks and version comparison remain the use case/store's responsibilities.
     *
     * @throws IllegalArgumentException with fixed diagnostics for invalid data/revision/scope/configuration
     * @throws CentralUnavailableException with fixed diagnostics for credential, HTTP or transport unavailability
     */
    @Override
    public WhitelistSnapshot fetchSnapshot(String kioskId, String factoryId) {
        if (!this.kioskId.equals(kioskId) || !this.factoryId.equals(factoryId)) {
            throw invalidSnapshot();
        }
        try {
            WhitelistHttpTransport.SnapshotResponse response = transport.fetchSnapshotResponse();
            if (!this.factoryId.equals(response.factoryId())) {
                throw invalidSnapshot();
            }
            long version = sourceVersion(response.versionHeaders());
            List<WhitelistEmployee> employees = mapper.mapEmployees(response.employees());
            return new WhitelistSnapshot(new WhitelistMetadata(this.kioskId, this.factoryId, version,
                    clock.instant(), employees.size()), employees);
        } catch (WhitelistHttpException failure) {
            throw switch (failure.reason()) {
                case INVALID_RESPONSE, INVALID_CONFIGURATION -> invalidSnapshot();
                case MISSING_CREDENTIAL, HTTP_ERROR, UNAVAILABLE ->
                    new CentralUnavailableException("whitelist snapshot source is unavailable");
            };
        } catch (IllegalArgumentException invalidData) {
            // Mapping/parsing failures never retain upstream values, causes or body excerpts.
            throw invalidSnapshot();
        }
    }

    private static long sourceVersion(List<String> headers) {
        long version = 0;
        for (String header : headers) {
            for (String part : header.split(",", -1)) {
                String value = part.trim();
                if (!value.matches("[0-9]+")) {
                    throw invalidSnapshot();
                }
                long candidate = Long.parseLong(value);
                if (candidate <= 0 || (version != 0 && candidate != version)) {
                    throw invalidSnapshot();
                }
                version = candidate;
            }
        }
        if (version == 0) {
            throw invalidSnapshot();
        }
        return version;
    }

    private static String configuredId(String value) {
        if (value == null || value.isBlank()) {
            throw invalidSnapshot();
        }
        return value.strip();
    }

    private static IllegalArgumentException invalidSnapshot() {
        return new IllegalArgumentException("invalid whitelist snapshot response or scope");
    }
}
