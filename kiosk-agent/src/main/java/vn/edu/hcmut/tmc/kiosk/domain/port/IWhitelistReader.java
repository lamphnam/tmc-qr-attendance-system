package vn.edu.hcmut.tmc.kiosk.domain.port;

import java.util.Optional;

import vn.edu.hcmut.tmc.kiosk.domain.exception.WhitelistCacheException;
import vn.edu.hcmut.tmc.kiosk.domain.model.WhitelistLookup;
import vn.edu.hcmut.tmc.kiosk.domain.model.WhitelistMetadata;

/** Local read capability for scan/startup clients; never fetches central data or changes the cache. */
public interface IWhitelistReader {

    /**
     * Returns metadata only for a consistent snapshot in this kiosk's configured scope; count zero is valid.
     *
     * @return empty only when no snapshot has been initialized, never for a storage or integrity failure
     * @throws WhitelistCacheException if storage is unreadable, stored material cannot be recovered,
     *                                 or the cache/scope is inconsistent
     */
    Optional<WhitelistMetadata> inspect();

    /**
     * Reads metadata and an employee from the same consistent local snapshot. Missing metadata denotes an
     * uninitialized cache; present metadata with an absent employee denotes a miss (including an empty snapshot).
     * Does not perform QR authorization, expiry checks or TOTP verification. An employee may legitimately have
     * absent material as defined by WhitelistEmployee; this is distinct from failure to recover stored material.
     * Returned material is sensitive.
     *
     * @param employeeId non-blank employee id; surrounding whitespace is stripped, case is preserved
     * @return a non-null lookup; an employee, when present, has the requested normalized id
     * @throws IllegalArgumentException if employeeId is null or blank
     * @throws WhitelistCacheException if storage is unreadable, stored material cannot be recovered,
     *                                 or the cache/scope is inconsistent
     */
    WhitelistLookup lookup(String employeeId);
}
