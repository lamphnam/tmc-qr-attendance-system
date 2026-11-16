package vn.edu.hcmut.tmc.userqr.domain.repository;

import vn.edu.hcmut.tmc.userqr.domain.model.AuditEvent;
import vn.edu.hcmut.tmc.userqr.domain.model.AuditLog;

/** Append-only audit writer. There is deliberately no update/delete capability. */
public interface IAuditLogRepository {
    /** Requires an active READ_COMMITTED transaction. Serializes appends across writers, links the last committed
     * checksum (64 zeroes for genesis), and inserts exactly one event with its SHA-256 checksum.
     * Returns the inserted log, but never commits independently. Duplicate IDs/storage failure
     * throw QrStorageException and must roll back the employee change as well. */
    AuditLog append(AuditEvent event);
}
