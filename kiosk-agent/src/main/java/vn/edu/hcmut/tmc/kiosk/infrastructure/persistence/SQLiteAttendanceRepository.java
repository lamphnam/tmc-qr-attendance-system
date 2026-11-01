package vn.edu.hcmut.tmc.kiosk.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import vn.edu.hcmut.tmc.kiosk.domain.model.Direction;
import vn.edu.hcmut.tmc.kiosk.domain.model.GateCommandStatus;
import vn.edu.hcmut.tmc.kiosk.domain.model.LocalAttendanceEvent;
import vn.edu.hcmut.tmc.kiosk.domain.model.QueueCounts;
import vn.edu.hcmut.tmc.kiosk.domain.model.ScanResult;
import vn.edu.hcmut.tmc.kiosk.domain.model.SyncStatus;
import vn.edu.hcmut.tmc.kiosk.domain.port.IAttendanceLocalRepository;

/**
 * SQLite adapter for the durable queue. Each statement is its own auto-committed transaction, so when
 * {@link #insertPending} returns the row is committed (WAL, synchronous=FULL - see {@link SqliteConfig}).
 *
 * <p>Timestamps are stored as fixed-width UTC text ({@code yyyy-MM-dd'T'HH:mm:ss.SSS'Z'}) so that string comparison
 * in SQL ({@code next_retry_at <= ?}) is also chronological comparison.
 */
@Repository
public class SQLiteAttendanceRepository implements IAttendanceLocalRepository {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");

    private static final String COLUMNS = """
            attendance_id, employee_id, factory_id, gate_id, direction, occurred_at, scan_result, sync_status,
            gate_command_status, gate_command_detail, retry_count, next_retry_at, last_error, dev_fixture,
            created_at, synced_at""";

    private static final RowMapper<LocalAttendanceEvent> ROW_MAPPER = SQLiteAttendanceRepository::mapRow;

    private final JdbcTemplate jdbc;

    public SQLiteAttendanceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insertPending(LocalAttendanceEvent e) {
        jdbc.update("""
                        INSERT INTO local_attendance_events
                            (attendance_id, employee_id, factory_id, gate_id, direction, occurred_at, scan_result,
                             sync_status, gate_command_status, retry_count, dev_fixture, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', 'NOT_ATTEMPTED', 0, ?, ?)""",
                e.attendanceId().toString(), e.employeeId(), e.factoryId(), e.gateId(), e.direction().name(),
                format(e.occurredAt()), e.scanResult().name(), e.devFixture() ? 1 : 0, format(e.createdAt()));
    }

    @Override
    public void updateGateCommandStatus(UUID attendanceId, GateCommandStatus status, String detail) {
        jdbc.update("UPDATE local_attendance_events SET gate_command_status = ?, gate_command_detail = ? WHERE attendance_id = ?",
                status.name(), detail, attendanceId.toString());
    }

    @Override
    public List<LocalAttendanceEvent> findDueForSync(Instant now, int limit) {
        return jdbc.query("SELECT " + COLUMNS + """
                         FROM local_attendance_events
                        WHERE sync_status = 'PENDING' AND (next_retry_at IS NULL OR next_retry_at <= ?)
                        ORDER BY created_at, rowid
                        LIMIT ?""", ROW_MAPPER, format(now), limit);
    }

    @Override
    public boolean markSynced(UUID attendanceId, Instant syncedAt) {
        return jdbc.update("""
                UPDATE local_attendance_events
                   SET sync_status = 'SYNCED', synced_at = ?, last_error = NULL, next_retry_at = NULL
                 WHERE attendance_id = ? AND sync_status = 'PENDING'""", format(syncedAt), attendanceId.toString()) == 1;
    }

    @Override
    public boolean markFailed(UUID attendanceId, String reason) {
        return jdbc.update("""
                UPDATE local_attendance_events SET sync_status = 'FAILED', last_error = ?, next_retry_at = NULL
                 WHERE attendance_id = ? AND sync_status = 'PENDING'""", reason, attendanceId.toString()) == 1;
    }

    @Override
    public boolean scheduleRetry(UUID attendanceId, Instant nextRetryAt, String reason) {
        return jdbc.update("""
                UPDATE local_attendance_events
                   SET retry_count = retry_count + 1, next_retry_at = ?, last_error = ?
                 WHERE attendance_id = ? AND sync_status = 'PENDING'""", format(nextRetryAt), reason, attendanceId.toString()) == 1;
    }

    @Override
    public Optional<LocalAttendanceEvent> findById(UUID attendanceId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM local_attendance_events WHERE attendance_id = ?",
                ROW_MAPPER, attendanceId.toString()).stream().findFirst();
    }

    @Override
    public List<LocalAttendanceEvent> findRecent(int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM local_attendance_events ORDER BY created_at DESC, rowid DESC LIMIT ?",
                ROW_MAPPER, limit);
    }

    @Override
    public QueueCounts countByStatus() {
        long[] counts = new long[SyncStatus.values().length];
        jdbc.query("SELECT sync_status, COUNT(*) FROM local_attendance_events GROUP BY sync_status",
                rs -> {
                    counts[SyncStatus.valueOf(rs.getString(1)).ordinal()] = rs.getLong(2);
                });
        return new QueueCounts(counts[SyncStatus.PENDING.ordinal()], counts[SyncStatus.SYNCED.ordinal()],
                counts[SyncStatus.FAILED.ordinal()]);
    }

    static String format(Instant instant) {
        return instant == null ? null : TS.format(LocalDateTime.ofInstant(instant, ZoneOffset.UTC));
    }

    static Instant parse(String text) {
        return text == null ? null : LocalDateTime.parse(text, TS).toInstant(ZoneOffset.UTC);
    }

    private static LocalAttendanceEvent mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new LocalAttendanceEvent(
                UUID.fromString(rs.getString("attendance_id")),
                rs.getString("employee_id"),
                rs.getString("factory_id"),
                rs.getString("gate_id"),
                Direction.valueOf(rs.getString("direction")),
                parse(rs.getString("occurred_at")),
                ScanResult.valueOf(rs.getString("scan_result")),
                SyncStatus.valueOf(rs.getString("sync_status")),
                GateCommandStatus.valueOf(rs.getString("gate_command_status")),
                rs.getString("gate_command_detail"),
                rs.getInt("retry_count"),
                parse(rs.getString("next_retry_at")),
                rs.getString("last_error"),
                rs.getInt("dev_fixture") == 1,
                parse(rs.getString("created_at")),
                parse(rs.getString("synced_at")));
    }
}
