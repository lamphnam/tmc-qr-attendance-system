package vn.edu.hcmut.tmc.userqr.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.edu.hcmut.tmc.userqr.domain.exception.QrStorageException;
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
        try {
            return factoryId == null
                    ? jdbc.query("SELECT " + COLUMNS + " FROM employees ORDER BY employee_id", MAPPER)
                    : jdbc.query("SELECT " + COLUMNS + " FROM employees WHERE factory_id = ? ORDER BY employee_id",
                            MAPPER, factoryId);
        } catch (DataAccessException e) { throw new QrStorageException(e); }
    }

    @Override
    public void updateQr(EmployeeQr employee) {
        requireTransaction();
        try {
            int rows = jdbc.update("""
                    UPDATE employees SET shared_secret = ?, qr_status = ?, secret_version = ?, updated_at = ?
                    WHERE employee_id = ?
                    """, employee.sharedSecret(), employee.qrStatus().name(), employee.secretVersion(),
                    java.sql.Timestamp.from(employee.updatedAt()), employee.employeeId());
            if (rows != 1) throw new IllegalStateException("Locked employee disappeared");
        } catch (DataAccessException e) { throw new QrStorageException(e); }
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("QR writes require an active transaction");
        }
    }
}
