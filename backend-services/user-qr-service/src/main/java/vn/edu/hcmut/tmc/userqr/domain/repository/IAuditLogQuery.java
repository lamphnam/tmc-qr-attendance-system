package vn.edu.hcmut.tmc.userqr.domain.repository;

import java.util.List;
import vn.edu.hcmut.tmc.userqr.domain.model.AuditLog;

/** Read capability for the demo audit viewer, separate from the append-only writer. */
public interface IAuditLogQuery {
    /** Requires limit in [1,200]. Returns newest first by sequence, never modifies logs.
     * Storage failures throw QrStorageException. */
    List<AuditLog> findRecent(int limit);
}
