package vn.edu.hcmut.tmc.userqr.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Objects;
import java.util.stream.Stream;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.edu.hcmut.tmc.userqr.domain.exception.QrStorageException;
import vn.edu.hcmut.tmc.userqr.domain.exception.EmployeeNotFoundException;
import vn.edu.hcmut.tmc.userqr.domain.exception.FactoryNotFoundException;
import vn.edu.hcmut.tmc.userqr.domain.model.EmployeeQr;
import vn.edu.hcmut.tmc.userqr.domain.model.QrStatus;
import vn.edu.hcmut.tmc.userqr.domain.repository.IEmployeeQrRepository;

public class PostgresEmployeeQrRepository implements IEmployeeQrRepository {
    private static final String COLUMNS = "employee_id, full_name, factory_id, department, role, "
            + "shared_secret, qr_status, secret_version, updated_at";
    private static final RowMapper<EmployeeQr> MAPPER = (rs, row) -> new EmployeeQr(
            rs.getString("employee_id"), rs.getString("full_name"), rs.getString("factory_id"),
            rs.getString("department"), rs.getString("role"), rs.getString("shared_secret"),
            QrStatus.valueOf(rs.getString("qr_status")), rs.getInt("secret_version"),
            rs.getTimestamp("updated_at").toInstant());
    private final JdbcTemplate jdbc;

    public PostgresEmployeeQrRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public Optional<EmployeeQr> findById(String employeeId) { return find(employeeId, false); }

    @Override
    public Optional<EmployeeQr> findByIdForUpdate(String employeeId) {
        requireTransaction();
        return find(employeeId, true);
    }

    private Optional<EmployeeQr> find(String employeeId, boolean lock) {
        try {
            return jdbc.query("SELECT " + COLUMNS + " FROM employees WHERE employee_id = ?"
                    + (lock ? " FOR UPDATE" : ""), MAPPER, employeeId).stream().findFirst();
        } catch (DataAccessException e) { throw new QrStorageException(e); }
    }

    @Override
    public List<EmployeeQr> findWhitelist(String factoryId) {
        Objects.requireNonNull(factoryId, "Factory scope is required");
        try {
            return jdbc.query("SELECT " + COLUMNS + " FROM employees WHERE factory_id = ? ORDER BY employee_id",
                    MAPPER, factoryId);
        } catch (DataAccessException e) { throw new QrStorageException(e); }
    }

    @Override
    public OptionalLong findWhitelistVersion(String factoryId) {
        Objects.requireNonNull(factoryId, "Factory scope is required");
        try {
            var versions = jdbc.queryForList("SELECT whitelist_version FROM factories WHERE factory_id = ?",
                    Long.class, factoryId);
            return versions.isEmpty() ? OptionalLong.empty() : OptionalLong.of(versions.getFirst());
        } catch (DataAccessException e) { throw new QrStorageException(e); }
    }

    @Override
    public void updateQr(EmployeeQr employee) {
        requireTransaction();
        EmployeeQr before = findByIdForUpdate(employee.employeeId()).orElseThrow(EmployeeNotFoundException::new);
        try {
            bumpRevisions(before.factoryId(), employee.factoryId());
            int rows = jdbc.update("""
                    UPDATE employees SET shared_secret = ?, qr_status = ?, secret_version = ?, updated_at = ?, factory_id = ?
                    WHERE employee_id = ?
                    """, employee.sharedSecret(), employee.qrStatus().name(), employee.secretVersion(),
                    java.sql.Timestamp.from(employee.updatedAt()), employee.factoryId(), employee.employeeId());
            if (rows != 1) throw new IllegalStateException("Locked employee disappeared");
        } catch (DataAccessException e) { throw new QrStorageException(e); }
    }

    @Override
    public void insert(EmployeeQr employee) {
        requireTransaction();
        try {
            bumpRevisions(employee.factoryId());
            jdbc.update("INSERT INTO employees (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    employee.employeeId(), employee.fullName(), employee.factoryId(), employee.department(),
                    employee.role(), employee.sharedSecret(), employee.qrStatus().name(), employee.secretVersion(),
                    java.sql.Timestamp.from(employee.updatedAt()));
        } catch (DataAccessException e) { throw new QrStorageException(e); }
    }

    @Override
    public void delete(String employeeId) {
        requireTransaction();
        EmployeeQr before = findByIdForUpdate(employeeId).orElseThrow(EmployeeNotFoundException::new);
        try {
            bumpRevisions(before.factoryId());
            jdbc.update("DELETE FROM employees WHERE employee_id = ?", employeeId);
        } catch (DataAccessException e) { throw new QrStorageException(e); }
    }

    private void bumpRevisions(String... factoryIds) {
        // Every writer uses the same order, including opposite-direction factory transfers.
        for (String id : Stream.of(factoryIds).distinct().sorted().toList()) {
            if (jdbc.update("UPDATE factories SET whitelist_version = whitelist_version + 1 WHERE factory_id = ?", id) != 1) {
                throw new FactoryNotFoundException();
            }
        }
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("QR writes require an active transaction");
        }
    }
}
