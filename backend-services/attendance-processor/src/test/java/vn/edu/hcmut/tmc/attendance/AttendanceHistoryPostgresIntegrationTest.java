package vn.edu.hcmut.tmc.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import vn.edu.hcmut.tmc.attendance.domain.model.AttendanceHistoryPage;
import vn.edu.hcmut.tmc.attendance.domain.model.AttendanceHistoryQuery;
import vn.edu.hcmut.tmc.attendance.domain.model.AttendanceRecord;
import vn.edu.hcmut.tmc.attendance.domain.model.Direction;
import vn.edu.hcmut.tmc.attendance.domain.model.ScanResult;
import vn.edu.hcmut.tmc.attendance.infrastructure.persistence.PostgresAttendanceHistoryQuery;

/** Real PostgreSQL with the canonical schema; no H2 substitute and no duplicated DDL. */
@Testcontainers(disabledWithoutDocker = true)
class AttendanceHistoryPostgresIntegrationTest {

    private static final Instant FROM = Instant.parse("2026-11-14T01:00:00Z");
    private static final Instant TO = FROM.plusSeconds(60);

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcTemplate jdbc;
    private PostgresAttendanceHistoryQuery history;

    @BeforeAll
    static void applyCanonicalSchema() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("../../init-db/schema.sql"));
        }
        jdbc = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void cleanIsolatedTestDatabase() {
        jdbc.update("DELETE FROM attendance_events");
        history = new PostgresAttendanceHistoryQuery(jdbc);
    }

    private static UUID id(int value) {
        return new UUID(0, value);
    }

    private void insert(int value, String employeeId, String factoryId, Instant occurredAt, ScanResult result, boolean fixture) {
        jdbc.update("""
                INSERT INTO attendance_events (attendance_id, employee_id, factory_id, gate_id, direction,
                                               occurred_at, scan_result, source_kiosk_id, dev_fixture)
                VALUES (?, ?, ?, 'BD-G01', 'IN', ?, ?, 'BD-K01', ?)
                """, id(value), employeeId, factoryId, OffsetDateTime.ofInstant(occurredAt, ZoneOffset.UTC), result.name(), fixture);
    }

    private AttendanceHistoryPage page(String employeeId, String factoryId, int pageNumber, int pageSize) {
        return history.find(new AttendanceHistoryQuery(employeeId, factoryId, FROM, TO, pageNumber, pageSize));
    }

    @Test
    void filtersByBothEmployeeAndFactoryAndMapsTheStoredFact() {
        insert(1, "EMP001", "BD", FROM, ScanResult.ALLOWED, false);
        insert(2, "EMP002", "BD", FROM, ScanResult.ALLOWED, false);
        insert(3, "EMP001", "LA", FROM, ScanResult.ALLOWED, false);
        insert(4, "EMP002", "LA", FROM, ScanResult.ALLOWED, false);

        AttendanceHistoryPage result = page("EMP001", "BD", 0, 20);

        assertThat(result.events()).containsExactly(new AttendanceRecord(id(1), "EMP001", "BD", "BD-G01",
                Direction.IN, FROM, ScanResult.ALLOWED, "BD-K01", false));
        assertThat(result.hasNext()).isFalse();
        assertThat(page("EMP001", "LA", 0, 20).events()).extracting(AttendanceRecord::attendanceId).containsExactly(id(3));
    }

    @Test
    void includesTheStartAndExcludesTheEndOfTheRange() {
        insert(1, "EMP001", "BD", FROM.minusMillis(1), ScanResult.ALLOWED, false);
        insert(2, "EMP001", "BD", FROM, ScanResult.ALLOWED, false);
        insert(3, "EMP001", "BD", FROM.plusSeconds(1), ScanResult.ALLOWED, false);
        insert(4, "EMP001", "BD", TO.minusMillis(1), ScanResult.ALLOWED, false);
        insert(5, "EMP001", "BD", TO, ScanResult.ALLOWED, false);

        assertThat(page("EMP001", "BD", 0, 20).events()).extracting(AttendanceRecord::attendanceId)
                .containsExactly(id(2), id(3), id(4));
    }

    @Test
    void pagesHaveStableTimestampAndIdOrderEvenWithTiesAndOutOfOrderInserts() {
        insert(4, "EMP001", "BD", FROM.plusSeconds(1), ScanResult.ALLOWED, false);
        insert(3, "EMP001", "BD", FROM, ScanResult.ALLOWED, false);
        insert(1, "EMP001", "BD", FROM, ScanResult.ALLOWED, false);
        insert(2, "EMP001", "BD", FROM, ScanResult.ALLOWED, false);

        AttendanceHistoryPage first = page("EMP001", "BD", 0, 2);
        AttendanceHistoryPage second = page("EMP001", "BD", 1, 2);

        assertThat(first.events()).extracting(AttendanceRecord::attendanceId).containsExactly(id(1), id(2));
        assertThat(first.hasNext()).isTrue();
        assertThat(second.events()).extracting(AttendanceRecord::attendanceId).containsExactly(id(3), id(4));
        assertThat(second.hasNext()).isFalse();
        assertThat(second.pageNumber()).isEqualTo(1);
        assertThat(second.pageSize()).isEqualTo(2);
        assertThat(page("EMP001", "BD", 0, 2)).isEqualTo(first);
        assertThat(page("EMP001", "BD", 2, 2).events()).isEmpty();
    }

    @Test
    void excludesFixturesBeforePaginationAndIncludesBothStoredScanResults() {
        insert(1, "EMP001", "BD", FROM, ScanResult.ALLOWED, false);
        insert(2, "EMP001", "BD", FROM, ScanResult.ALLOWED, true);
        insert(3, "EMP001", "BD", FROM, ScanResult.REJECTED, false);
        insert(4, "EMP001", "BD", FROM, ScanResult.REJECTED, true);

        AttendanceHistoryPage first = page("EMP001", "BD", 0, 1);
        AttendanceHistoryPage second = page("EMP001", "BD", 1, 1);

        assertThat(first.events()).extracting(AttendanceRecord::attendanceId).containsExactly(id(1));
        assertThat(first.hasNext()).isTrue();
        assertThat(second.events()).extracting(AttendanceRecord::attendanceId).containsExactly(id(3));
        assertThat(second.events()).allSatisfy(record -> {
            assertThat(record.devFixture()).isFalse();
            assertThat(record.scanResult()).isEqualTo(ScanResult.REJECTED);
        });
        assertThat(second.hasNext()).isFalse();
    }

    @Test
    void noMatchReturnsAnEmptyPageWithTheRequestedPagination() {
        insert(1, "EMP001", "BD", FROM, ScanResult.ALLOWED, false);

        AttendanceHistoryPage result = page("UNKNOWN", "BD", 3, 20);

        assertThat(result.events()).isEmpty();
        assertThat(result.hasNext()).isFalse();
        assertThat(result.pageNumber()).isEqualTo(3);
        assertThat(result.pageSize()).isEqualTo(20);
    }

    @Test
    void treatsSqlSyntaxInEmployeeAndFactoryAsLiteralValues() {
        insert(1, "EMP001", "BD", FROM, ScanResult.ALLOWED, false);
        insert(2, "EMP002", "LA", FROM, ScanResult.ALLOWED, false);
        String literal = "x' OR '1'='1";

        assertThat(page(literal, "BD", 0, 20).events()).isEmpty();
        assertThat(page("EMP001", literal, 0, 20).events()).isEmpty();
        insert(3, literal, literal, FROM, ScanResult.ALLOWED, false);

        assertThat(page(literal, literal, 0, 20).events()).extracting(AttendanceRecord::attendanceId).containsExactly(id(3));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM attendance_events", Integer.class)).isEqualTo(3);
    }

    @Test
    void maximumPageSizeStillUsesOneExtraRowToDetectTheNextPage() {
        for (int value = 1; value <= 101; value++) {
            insert(value, "EMP001", "BD", FROM.plusMillis(value), ScanResult.ALLOWED, false);
        }

        AttendanceHistoryPage first = page("EMP001", "BD", 0, 100);
        AttendanceHistoryPage last = page("EMP001", "BD", 1, 100);

        assertThat(first.events()).hasSize(100);
        assertThat(first.hasNext()).isTrue();
        assertThat(last.events()).extracting(AttendanceRecord::attendanceId).containsExactly(id(101));
        assertThat(last.hasNext()).isFalse();
    }

    @Test
    void aLargeOffsetDoesNotOverflowAndNullQueriesAreRejected() {
        insert(1, "EMP001", "BD", FROM, ScanResult.ALLOWED, false);

        assertThat(page("EMP001", "BD", Integer.MAX_VALUE, 100).events()).isEmpty();
        assertThatThrownBy(() -> history.find(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
