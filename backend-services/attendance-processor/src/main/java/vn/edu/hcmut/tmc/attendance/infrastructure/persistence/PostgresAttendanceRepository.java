package vn.edu.hcmut.tmc.attendance.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import vn.edu.hcmut.tmc.attendance.domain.exception.AttendanceRejectedException;
import vn.edu.hcmut.tmc.attendance.domain.exception.AttendanceStorageException;
import vn.edu.hcmut.tmc.attendance.domain.model.AttendanceRecord;
import vn.edu.hcmut.tmc.attendance.domain.model.Direction;
import vn.edu.hcmut.tmc.attendance.domain.model.ScanResult;
import vn.edu.hcmut.tmc.attendance.domain.repository.IAttendanceRepository;

/** PostgreSQL adapter. The {@code attendance_id} primary key is the ultimate idempotency guard. */
@Repository
public class PostgresAttendanceRepository implements IAttendanceRepository {

    private static final String INSERT_IF_ABSENT = """
            INSERT INTO attendance_events
                (attendance_id, employee_id, factory_id, gate_id, direction, occurred_at,
                 scan_result, source_kiosk_id, dev_fixture)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (attendance_id) DO NOTHING
            """;

    private static final String SELECT_BY_ID = """
            SELECT attendance_id, employee_id, factory_id, gate_id, direction, occurred_at,
                   scan_result, source_kiosk_id, dev_fixture
            FROM attendance_events WHERE attendance_id = ?
            """;

    private static final RowMapper<AttendanceRecord> ROW_MAPPER = PostgresAttendanceRepository::mapRow;

    private final JdbcTemplate jdbc;

    public PostgresAttendanceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insertIfAbsent(AttendanceRecord r) {
        try {
            int rows = jdbc.update(INSERT_IF_ABSENT,
                    r.attendanceId(),
                    r.employeeId(),
                    r.factoryId(),
                    r.gateId(),
                    r.direction().name(),
                    OffsetDateTime.ofInstant(r.occurredAt(), ZoneOffset.UTC),
                    r.scanResult().name(),
                    r.sourceKioskId(),
                    r.devFixture());
            return rows == 1;
        } catch (DataIntegrityViolationException e) {
            throw new AttendanceRejectedException("event violates database constraints", e);
        } catch (DataAccessException e) {
            throw new AttendanceStorageException("central database unavailable", e);
        }
    }

    @Override
    public Optional<AttendanceRecord> findById(UUID attendanceId) {
        try {
            return jdbc.query(SELECT_BY_ID, ROW_MAPPER, attendanceId).stream().findFirst();
        } catch (DataAccessException e) {
            throw new AttendanceStorageException("central database unavailable", e);
        }
    }

    private static AttendanceRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new AttendanceRecord(
                rs.getObject("attendance_id", UUID.class),
                rs.getString("employee_id"),
                rs.getString("factory_id"),
                rs.getString("gate_id"),
                Direction.valueOf(rs.getString("direction")),
                rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                ScanResult.valueOf(rs.getString("scan_result")),
                rs.getString("source_kiosk_id"),
                rs.getBoolean("dev_fixture"));
    }
}
