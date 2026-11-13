package vn.edu.hcmut.tmc.userqr.infrastructure.persistence;

import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.edu.hcmut.tmc.userqr.domain.exception.QrStorageException;
import vn.edu.hcmut.tmc.userqr.domain.model.AuditEvent;
import vn.edu.hcmut.tmc.userqr.domain.model.AuditLog;
import vn.edu.hcmut.tmc.userqr.domain.repository.IAuditLogQuery;
import vn.edu.hcmut.tmc.userqr.domain.repository.IAuditLogRepository;
import vn.edu.hcmut.tmc.userqr.domain.service.AuditChecksumCalculator;

public class PostgresAuditLogRepository implements IAuditLogRepository, IAuditLogQuery {
    // A transaction-scoped lock also serializes the first append, when no previous row exists.
    private static final long AUDIT_CHAIN_LOCK = 730202601L;
    private static final RowMapper<AuditLog> MAPPER = (rs, row) -> new AuditLog(rs.getLong("audit_sequence"),
            new AuditEvent(rs.getString("log_id"), rs.getTimestamp("timestamp").toInstant(),
                    rs.getString("actor_id"), rs.getString("actor_role"), rs.getString("action_type"),
                    rs.getString("target_resource"), rs.getString("payload_summary"), rs.getString("status")),
            rs.getString("previous_hash"), rs.getString("checksum"));
    private final JdbcTemplate jdbc;
    private final AuditChecksumCalculator checksums;

    public PostgresAuditLogRepository(JdbcTemplate jdbc, AuditChecksumCalculator checksums) {
        this.jdbc = jdbc;
        this.checksums = checksums;
    }

    @Override
    public AuditLog append(AuditEvent event) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Audit append requires an active transaction");
        }
        try {
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(?)", (rs, row) -> true, AUDIT_CHAIN_LOCK);
            String previousHash = jdbc.query("SELECT checksum FROM audit_logs ORDER BY audit_sequence DESC LIMIT 1",
                    (rs, row) -> rs.getString(1)).stream().findFirst().orElse(AuditChecksumCalculator.GENESIS_HASH);
            String checksum = checksums.calculate(event, previousHash);
            Long sequence = jdbc.queryForObject("""
                    INSERT INTO audit_logs (log_id, timestamp, actor_id, actor_role, action_type,
                        target_resource, payload_summary, status, previous_hash, checksum)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING audit_sequence
                    """, Long.class, event.logId(), java.sql.Timestamp.from(event.timestamp()), event.actorId(),
                    event.actorRole(), event.actionType(), event.targetResource(), event.payloadSummary(),
                    event.status(), previousHash, checksum);
            return new AuditLog(sequence, event, previousHash, checksum);
        } catch (DataAccessException e) { throw new QrStorageException(e); }
    }

    @Override
    public List<AuditLog> findRecent(int limit) {
        if (limit < 1 || limit > 200) throw new IllegalArgumentException("Audit limit must be in [1,200]");
        try {
            return jdbc.query("SELECT * FROM audit_logs ORDER BY audit_sequence DESC LIMIT ?", MAPPER, limit);
        } catch (DataAccessException e) { throw new QrStorageException(e); }
    }
}
