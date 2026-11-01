package vn.edu.hcmut.tmc.kiosk.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import vn.edu.hcmut.tmc.kiosk.domain.model.Direction;
import vn.edu.hcmut.tmc.kiosk.domain.model.GateCommandStatus;
import vn.edu.hcmut.tmc.kiosk.domain.model.LocalAttendanceEvent;
import vn.edu.hcmut.tmc.kiosk.domain.model.QueueCounts;
import vn.edu.hcmut.tmc.kiosk.domain.model.ScanResult;
import vn.edu.hcmut.tmc.kiosk.domain.model.SyncStatus;

/** Real SQLite engine, real file, production {@link SqliteConfig} - no in-memory substitute. */
class SQLiteAttendanceRepositoryTest {

    private static final Instant T0 = Instant.parse("2026-11-01T01:00:00Z");

    @TempDir
    Path dir;

    private Path file;
    private JdbcTemplate jdbc;
    private SQLiteAttendanceRepository repository;

    @BeforeEach
    void setUp() {
        file = dir.resolve("kiosk.db");
        open();
    }

    /** Opening again re-runs the schema script and uses brand-new connections: this is what a process restart does. */
    private void open() {
        DataSource dataSource = SqliteConfig.createDataSource(file);
        jdbc = new JdbcTemplate(dataSource);
        repository = new SQLiteAttendanceRepository(jdbc);
    }

    private static LocalAttendanceEvent event(UUID id, Instant createdAt) {
        return LocalAttendanceEvent.newPending(id, "EMP001", "BD", "BD-G01", Direction.IN, createdAt, ScanResult.ALLOWED, true, createdAt);
    }

    @Test
    void connectionsUseWalJournalAndFullSynchronous() {
        assertThat(jdbc.queryForObject("PRAGMA journal_mode", String.class)).isEqualToIgnoringCase("wal");
        assertThat(jdbc.queryForObject("PRAGMA synchronous", Integer.class)).isEqualTo(2);
    }

    @Test
    void pendingEventSurvivesReopeningTheDatabaseFile() {
        UUID id = UUID.randomUUID();
        repository.insertPending(event(id, T0));

        open();

        LocalAttendanceEvent reloaded = repository.findById(id).orElseThrow();
        assertThat(reloaded.syncStatus()).isEqualTo(SyncStatus.PENDING);
        assertThat(reloaded.employeeId()).isEqualTo("EMP001");
        assertThat(reloaded.occurredAt()).isEqualTo(T0);
        assertThat(repository.countByStatus()).isEqualTo(new QueueCounts(1, 0, 0));
    }

    @Test
    void anAttendanceIdCanOnlyBeQueuedOnce() {
        UUID id = UUID.randomUUID();
        repository.insertPending(event(id, T0));

        assertThatThrownBy(() -> repository.insertPending(event(id, T0.plusSeconds(1)))).isInstanceOf(DataAccessException.class);
        assertThat(repository.countByStatus().pending()).isEqualTo(1);
    }

    @Test
    void onlyPendingRowsCanBeMarkedSyncedOrFailed() {
        UUID acked = UUID.randomUUID();
        UUID rejected = UUID.randomUUID();
        repository.insertPending(event(acked, T0));
        repository.insertPending(event(rejected, T0.plusSeconds(1)));

        assertThat(repository.markSynced(acked, T0.plusSeconds(5))).isTrue();
        assertThat(repository.markSynced(acked, T0.plusSeconds(6))).as("second ack is a no-op").isFalse();
        assertThat(repository.markFailed(rejected, "bad")).isTrue();
        assertThat(repository.markSynced(rejected, T0.plusSeconds(7))).as("FAILED is never resurrected").isFalse();

        assertThat(repository.countByStatus()).isEqualTo(new QueueCounts(0, 1, 1));
        assertThat(repository.findById(rejected).orElseThrow().lastError()).isEqualTo("bad");
    }

    @Test
    void scheduledRetryHidesTheEventUntilItIsDue() {
        UUID id = UUID.randomUUID();
        repository.insertPending(event(id, T0));

        assertThat(repository.scheduleRetry(id, T0.plusSeconds(10), "central down")).isTrue();

        assertThat(repository.findDueForSync(T0.plusSeconds(9), 10)).isEmpty();
        List<LocalAttendanceEvent> due = repository.findDueForSync(T0.plusSeconds(10), 10);
        assertThat(due).singleElement().satisfies(e -> {
            assertThat(e.retryCount()).isEqualTo(1);
            assertThat(e.nextRetryAt()).isEqualTo(T0.plusSeconds(10));
            assertThat(e.syncStatus()).isEqualTo(SyncStatus.PENDING);
        });
    }

    @Test
    void dueEventsComeOldestFirstAndRespectTheLimit() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        repository.insertPending(event(second, T0.plusSeconds(1)));
        repository.insertPending(event(first, T0));
        repository.insertPending(event(third, T0.plusSeconds(2)));

        List<LocalAttendanceEvent> due = repository.findDueForSync(T0.plusSeconds(60), 2);

        assertThat(due).extracting(LocalAttendanceEvent::attendanceId).containsExactly(first, second);
    }

    @Test
    void gateCommandOutcomeIsTrackedSeparatelyFromSyncStatus() {
        UUID id = UUID.randomUUID();
        repository.insertPending(event(id, T0));

        repository.updateGateCommandStatus(id, GateCommandStatus.FAILED, "turnstile unreachable");

        LocalAttendanceEvent reloaded = repository.findById(id).orElseThrow();
        assertThat(reloaded.gateCommandStatus()).isEqualTo(GateCommandStatus.FAILED);
        assertThat(reloaded.gateCommandDetail()).isEqualTo("turnstile unreachable");
        assertThat(reloaded.syncStatus()).as("a gate failure must not touch the central sync state").isEqualTo(SyncStatus.PENDING);
    }
}
