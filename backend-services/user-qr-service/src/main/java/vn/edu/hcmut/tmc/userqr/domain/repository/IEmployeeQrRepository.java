package vn.edu.hcmut.tmc.userqr.domain.repository;

import java.util.List;
import java.util.Optional;
import vn.edu.hcmut.tmc.userqr.domain.model.EmployeeQr;

/** Employee QR persistence, with no HTTP or JDBC types in its contract.
 * All storage failures throw QrStorageException; absence is Optional.empty(). */
public interface IEmployeeQrRepository {
    Optional<EmployeeQr> findById(String employeeId);

    /** Requires an active transaction. Locks the employee until commit/rollback, serializing lifecycle changes. */
    Optional<EmployeeQr> findByIdForUpdate(String employeeId);

    /** Returns all statuses ordered by employeeId; null factoryId means all factories. No mutation. */
    List<EmployeeQr> findWhitelist(String factoryId);

    /** Requires a prior findByIdForUpdate in the same transaction. Updates only QR fields;
     * missing row is a storage failure. Does not commit independently. */
    void updateQr(EmployeeQr employee);
}
