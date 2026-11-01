package vn.edu.hcmut.tmc.kiosk.infrastructure.persistence;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import vn.edu.hcmut.tmc.kiosk.domain.model.QueueCounts;
import vn.edu.hcmut.tmc.kiosk.domain.port.IAttendanceLocalRepository;

/**
 * Readiness of the local store: actually opens the SQLite file and reads the queue table, and reports DOWN if the
 * journal is not in WAL mode (the durability assumption of ADR-01). Appears as {@code sqliteQueue} in /actuator/health.
 */
@Component
public class SqliteQueueHealthIndicator implements HealthIndicator {

    private final JdbcTemplate jdbc;
    private final IAttendanceLocalRepository repository;

    public SqliteQueueHealthIndicator(JdbcTemplate jdbc, IAttendanceLocalRepository repository) {
        this.jdbc = jdbc;
        this.repository = repository;
    }

    @Override
    public Health health() {
        try {
            String journalMode = jdbc.queryForObject("PRAGMA journal_mode", String.class);
            Integer synchronous = jdbc.queryForObject("PRAGMA synchronous", Integer.class);
            QueueCounts counts = repository.countByStatus();

            Health.Builder builder = "wal".equalsIgnoreCase(journalMode)
                    ? Health.up()
                    : Health.down().withDetail("reason", "journal_mode is '" + journalMode + "', expected 'wal'");
            return builder
                    .withDetail("journalMode", journalMode)
                    .withDetail("synchronous", synchronous != null && synchronous == 2 ? "FULL" : String.valueOf(synchronous))
                    .withDetail("pending", counts.pending())
                    .withDetail("synced", counts.synced())
                    .withDetail("failed", counts.failed())
                    .build();
        } catch (RuntimeException e) {
            return Health.down().withDetail("error", e.getClass().getSimpleName()).build();
        }
    }
}
