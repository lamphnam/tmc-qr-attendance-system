package vn.edu.hcmut.tmc.userqr.domain.repository;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import vn.edu.hcmut.tmc.userqr.domain.model.EmployeeQr;

/** Employee QR persistence, with no HTTP or JDBC types in its contract.
 * All storage failures throw QrStorageException; absence is Optional.empty(). */
public interface IEmployeeQrRepository {
    Optional<EmployeeQr> findById(String employeeId);

    /** Requires an active transaction. Locks the employee until commit/rollback, serializing lifecycle changes. */
    Optional<EmployeeQr> findByIdForUpdate(String employeeId);

    /** Non-null factory scope; returns all statuses ordered by employeeId. No mutation.
     * Read with findWhitelistVersion in one repeatable-read transaction for a consistent snapshot. */
    List<EmployeeQr> findWhitelist(String factoryId);

    /** Returns the factory's positive revision, or empty for an unknown factory. No mutation. */
    OptionalLong findWhitelistVersion(String factoryId);

    /** Requires a prior findByIdForUpdate in the same transaction. Updates QR fields and factory;
     * increments old/new factory revisions once each, locking factory IDs in lexical order.
     * Missing employee/factory throws EmployeeNotFoundException/FactoryNotFoundException.
     * Never commits independently; caller must append audit in the same transaction. */
    void updateQr(EmployeeQr employee);

    /** Requires an active transaction; inserts a new employee and increments its factory revision.
     * Duplicate IDs/storage errors throw QrStorageException, unknown factory throws FactoryNotFoundException.
     * Caller must append audit before commit; neither operation commits independently. */
    void insert(EmployeeQr employee);

    /** Requires a prior findByIdForUpdate in the same transaction; deletes the employee and
     * increments its factory revision. Missing employee throws EmployeeNotFoundException.
     * Storage/FK failures throw QrStorageException. Caller must append audit before commit. */
    void delete(String employeeId);
}
