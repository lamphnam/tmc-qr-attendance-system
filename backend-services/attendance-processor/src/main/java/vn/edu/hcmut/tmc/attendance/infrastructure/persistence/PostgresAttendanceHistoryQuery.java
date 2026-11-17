package vn.edu.hcmut.tmc.attendance.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import vn.edu.hcmut.tmc.attendance.domain.exception.AttendanceStorageException;
import vn.edu.hcmut.tmc.attendance.domain.model.AttendanceHistoryPage;
import vn.edu.hcmut.tmc.attendance.domain.model.AttendanceHistoryQuery;
import vn.edu.hcmut.tmc.attendance.domain.model.AttendanceRecord;
import vn.edu.hcmut.tmc.attendance.domain.model.Direction;
import vn.edu.hcmut.tmc.attendance.domain.model.ScanResult;
import vn.edu.hcmut.tmc.attendance.domain.repository.IAttendanceHistoryQuery;
import vn.edu.hcmut.tmc.attendance.domain.repository.IAttendanceEventsQuery;

/** A single parameterized SELECT against the existing central DataSource; no ingestion transaction is involved. */
@Repository
public class PostgresAttendanceHistoryQuery implements IAttendanceHistoryQuery, IAttendanceEventsQuery {

    private static final String SELECT_RECENT = """
            SELECT attendance_id, employee_id, factory_id, gate_id, direction, occurred_at,
                   scan_result, source_kiosk_id, dev_fixture
              FROM attendance_events
            """;

    private static final String SELECT_HISTORY = """
            SELECT attendance_id, employee_id, factory_id, gate_id, direction, occurred_at,
                   scan_result, source_kiosk_id, dev_fixture
              FROM attendance_events
             WHERE employee_id = ? AND factory_id = ?
               AND occurred_at >= ? AND occurred_at < ?
               AND dev_fixture = FALSE
             ORDER BY occurred_at ASC, attendance_id ASC
             LIMIT ? OFFSET ?
            """;
    private static final RowMapper<AttendanceRecord> ROW_MAPPER = PostgresAttendanceHistoryQuery::mapRow;

    private final JdbcTemplate jdbc;

    public PostgresAttendanceHistoryQuery(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public List<AttendanceRecord> findRecent(int limit, String employeeId) {
        try {
            // Only fixed SQL fragments are selected here; both filter and limit remain bound parameters.
            String sql = SELECT_RECENT + (employeeId == null ? "" : " WHERE employee_id = ?")
                    + " ORDER BY occurred_at DESC, attendance_id DESC LIMIT ?";
            return List.copyOf(employeeId == null ? jdbc.query(sql, ROW_MAPPER, limit)
                    : jdbc.query(sql, ROW_MAPPER, employeeId, limit));
        } catch (DataAccessException failure) {
            throw new AttendanceStorageException("attendance events storage unavailable", failure);
        }
    }

    @Override
    public AttendanceHistoryPage find(AttendanceHistoryQuery query) {
        if (query == null) {
            throw new IllegalArgumentException("query must not be null");
        }
        try {
            // Fetch one extra matching row instead of running a separate COUNT with a different read snapshot.
            List<AttendanceRecord> rows = jdbc.query(SELECT_HISTORY, ROW_MAPPER,
                    query.employeeId(), query.factoryId(),
                    OffsetDateTime.ofInstant(query.fromInclusive(), ZoneOffset.UTC),
                    OffsetDateTime.ofInstant(query.toExclusive(), ZoneOffset.UTC),
                    query.pageSize() + 1, query.offset());
            boolean hasNext = rows.size() > query.pageSize();
            return new AttendanceHistoryPage(rows.subList(0, Math.min(rows.size(), query.pageSize())),
                    query.pageNumber(), query.pageSize(), hasNext);
        } catch (DataAccessException failure) {
            throw new AttendanceStorageException("attendance history storage unavailable", failure);
        }
    }

    private static AttendanceRecord mapRow(ResultSet row, int rowNum) throws SQLException {
        return new AttendanceRecord(row.getObject("attendance_id", UUID.class),
                row.getString("employee_id"), row.getString("factory_id"), row.getString("gate_id"),
                Direction.valueOf(row.getString("direction")),
                row.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                ScanResult.valueOf(row.getString("scan_result")), row.getString("source_kiosk_id"),
                row.getBoolean("dev_fixture"));
    }
}
