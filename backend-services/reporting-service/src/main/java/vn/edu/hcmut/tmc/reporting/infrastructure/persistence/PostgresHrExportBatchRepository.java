package vn.edu.hcmut.tmc.reporting.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionOperations;

import vn.edu.hcmut.tmc.reporting.domain.exception.ReportingStorageException;
import vn.edu.hcmut.tmc.reporting.domain.model.BatchStatus;
import vn.edu.hcmut.tmc.reporting.domain.model.ExportPeriod;
import vn.edu.hcmut.tmc.reporting.domain.model.HrExportBatch;
import vn.edu.hcmut.tmc.reporting.domain.model.PayrollRecord;
import vn.edu.hcmut.tmc.reporting.domain.model.PayrollSelection;
import vn.edu.hcmut.tmc.reporting.domain.model.TriggerSource;
import vn.edu.hcmut.tmc.reporting.domain.port.IHrExportBatchStore;
import vn.edu.hcmut.tmc.reporting.domain.port.IHrExportStatusReader;

/**
 * PostgreSQL adapter for both export ports. One class serves the writer port (export use case) and the read-only port
 * (dashboard); each client sees only its own interface.
 *
 * <p>The primary key on {@code batch_uuid} (derived from the period) plus {@code ON CONFLICT DO NOTHING} makes batch
 * creation safe under concurrency; the item freeze runs in the same transaction, so a batch never exists without its
 * items.
 */
@Repository
public class PostgresHrExportBatchRepository implements IHrExportBatchStore, IHrExportStatusReader {

    private static final String COLUMNS = """
            b.batch_uuid, b.period, b.status, b.trigger_source, b.created_at, b.retry_deadline, b.attempt_count,
            b.last_attempt_at, b.next_retry_at, b.last_error,
            (SELECT count(*) FROM hr_export_batch_items i WHERE i.batch_uuid = b.batch_uuid) AS item_count
            """;

    private static final String INSERT_BATCH = """
            INSERT INTO hr_export_batches
                (batch_uuid, period, status, trigger_source, created_at, retry_deadline, attempt_count, next_retry_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT DO NOTHING
            """;

    private static final String FREEZE_ITEMS = """
            INSERT INTO hr_export_batch_items (batch_uuid, attendance_id)
            SELECT ?, e.attendance_id
            FROM attendance_events e
            WHERE e.occurred_at >= ? AND e.occurred_at < ?
              AND e.scan_result = 'ALLOWED'
              AND (? OR NOT e.dev_fixture)
            """;

    private static final String SELECT_BY_PERIOD = "SELECT " + COLUMNS + " FROM hr_export_batches b WHERE b.period = ?";

    private static final String SELECT_LATEST = "SELECT " + COLUMNS
            + " FROM hr_export_batches b ORDER BY b.period DESC LIMIT 1";

    private static final String SELECT_DUE = "SELECT " + COLUMNS + """
             FROM hr_export_batches b
            WHERE b.status IN ('PENDING', 'SENDING') AND b.next_retry_at <= ?
            ORDER BY b.next_retry_at, b.period
            LIMIT ?
            """;

    private static final String COMPARE_AND_SET = """
            UPDATE hr_export_batches
               SET status = ?, trigger_source = ?, retry_deadline = ?, attempt_count = ?,
                   last_attempt_at = ?, next_retry_at = ?, last_error = ?
             WHERE batch_uuid = ? AND status = ? AND attempt_count = ?
            """;

    private static final String SELECT_RECORDS = """
            SELECT e.attendance_id, e.employee_id, e.factory_id, e.gate_id, e.direction, e.occurred_at
            FROM hr_export_batch_items i
            JOIN attendance_events e ON e.attendance_id = i.attendance_id
            WHERE i.batch_uuid = ?
            ORDER BY e.occurred_at, e.attendance_id
            """;

    private static final int MAX_ERROR_LENGTH = 500;

    private final JdbcTemplate jdbc;
    private final TransactionOperations transactions;

    public PostgresHrExportBatchRepository(JdbcTemplate jdbc, TransactionOperations transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @Override
    public HrExportBatch createIfAbsent(HrExportBatch candidate, PayrollSelection selection) {
        if (!candidate.period().equals(selection.period())) {
            throw new IllegalArgumentException("the selection must be for the candidate's period");
        }
        return guarded(() -> transactions.execute(status -> {
            int inserted = jdbc.update(INSERT_BATCH,
                    candidate.batchUuid(),
                    candidate.period().label(),
                    candidate.status().name(),
                    candidate.triggerSource().name(),
                    utc(candidate.createdAt()),
                    utc(candidate.retryDeadline()),
                    candidate.attemptCount(),
                    utc(candidate.nextRetryAt()));
            if (inserted == 1) {
                jdbc.update(FREEZE_ITEMS,
                        candidate.batchUuid(),
                        utc(selection.period().startInclusive()),
                        utc(selection.period().endExclusive()),
                        selection.scope().includeDevFixtures());
            }
            return queryOne(SELECT_BY_PERIOD, candidate.period().label())
                    .orElseThrow(() -> new IllegalStateException("batch for " + candidate.period() + " vanished"));
        }));
    }

    @Override
    public Optional<HrExportBatch> find(ExportPeriod period) {
        return guarded(() -> queryOne(SELECT_BY_PERIOD, period.label()));
    }

    @Override
    public Optional<HrExportBatch> latest() {
        return guarded(() -> queryOne(SELECT_LATEST));
    }

    @Override
    public List<HrExportBatch> findDue(Instant now, int limit) {
        return guarded(() -> jdbc.query(SELECT_DUE, PostgresHrExportBatchRepository::mapBatch, utc(now), limit));
    }

    @Override
    public boolean compareAndSet(HrExportBatch expected, HrExportBatch next) {
        if (!expected.batchUuid().equals(next.batchUuid())) {
            throw new IllegalArgumentException("compareAndSet must target one batch");
        }
        return guarded(() -> jdbc.update(COMPARE_AND_SET,
                next.status().name(),
                next.triggerSource().name(),
                utc(next.retryDeadline()),
                next.attemptCount(),
                utc(next.lastAttemptAt()),
                utc(next.nextRetryAt()),
                truncate(next.lastError()),
                expected.batchUuid(),
                expected.status().name(),
                expected.attemptCount()) == 1);
    }

    @Override
    public List<PayrollRecord> loadRecords(UUID batchUuid) {
        return guarded(() -> jdbc.query(SELECT_RECORDS, (rs, rowNum) -> new PayrollRecord(
                rs.getObject("attendance_id", UUID.class),
                rs.getString("employee_id"),
                rs.getString("factory_id"),
                rs.getString("gate_id"),
                rs.getString("direction"),
                rs.getObject("occurred_at", OffsetDateTime.class).toInstant()), batchUuid));
    }

    private Optional<HrExportBatch> queryOne(String sql, Object... args) {
        return jdbc.query(sql, PostgresHrExportBatchRepository::mapBatch, args).stream().findFirst();
    }

    private static <T> T guarded(Supplier<T> action) {
        try {
            return action.get();
        } catch (DataAccessException | TransactionException e) {
            // TransactionException: with the DB down the transaction cannot even be opened, before any SQL runs.
            throw new ReportingStorageException("central database unavailable", e);
        }
    }

    private static HrExportBatch mapBatch(ResultSet rs, int rowNum) throws SQLException {
        return new HrExportBatch(
                rs.getObject("batch_uuid", UUID.class),
                ExportPeriod.parse(rs.getString("period")),
                BatchStatus.valueOf(rs.getString("status")),
                TriggerSource.valueOf(rs.getString("trigger_source")),
                instant(rs, "created_at"),
                instant(rs, "retry_deadline"),
                rs.getInt("attempt_count"),
                instant(rs, "last_attempt_at"),
                instant(rs, "next_retry_at"),
                rs.getString("last_error"),
                rs.getInt("item_count"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static String truncate(String error) {
        return error == null || error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
    }
}
