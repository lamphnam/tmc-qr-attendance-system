package vn.edu.hcmut.tmc.kiosk.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import vn.edu.hcmut.tmc.kiosk.application.usecase.QueueSyntheticEventUseCase;
import vn.edu.hcmut.tmc.kiosk.domain.model.Direction;
import vn.edu.hcmut.tmc.kiosk.domain.model.GateCommandResult;
import vn.edu.hcmut.tmc.kiosk.domain.model.GateCommandStatus;
import vn.edu.hcmut.tmc.kiosk.domain.model.KioskIdentity;
import vn.edu.hcmut.tmc.kiosk.domain.model.LocalAttendanceEvent;
import vn.edu.hcmut.tmc.kiosk.domain.model.SyncStatus;
import vn.edu.hcmut.tmc.kiosk.domain.port.ITurnstileAdapter;
import vn.edu.hcmut.tmc.kiosk.infrastructure.persistence.SQLiteAttendanceRepository;
import vn.edu.hcmut.tmc.kiosk.infrastructure.persistence.SqliteConfig;

/** Proves the ADR-01 ordering against a real SQLite file: durable PENDING row first, gate command second. */
class QueueSyntheticEventUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-11-01T01:00:00Z");
    private static final KioskIdentity IDENTITY = new KioskIdentity("BD-K01", "BD", "BD-G01");

    @TempDir
    Path dir;

    private SQLiteAttendanceRepository repository;

    @BeforeEach
    void setUp() {
        repository = new SQLiteAttendanceRepository(new JdbcTemplate(SqliteConfig.createDataSource(dir.resolve("kiosk.db"))));
    }

    private QueueSyntheticEventUseCase useCase(ITurnstileAdapter turnstile) {
        return new QueueSyntheticEventUseCase(repository, turnstile, IDENTITY, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void eventIsCommittedAsPendingBeforeTheGateCommandIsSent() {
        List<String> stateWhenCommandWasSent = new ArrayList<>();
        ITurnstileAdapter spy = gateId -> {
            // Repository calls use fresh connections, so seeing the row here means it was already COMMITTED.
            List<LocalAttendanceEvent> rows = repository.findRecent(10);
            stateWhenCommandWasSent.add(rows.size() + ":" + rows.get(0).syncStatus() + ":" + rows.get(0).gateCommandStatus());
            return new GateCommandResult(GateCommandStatus.SENT, "accepted");
        };

        LocalAttendanceEvent result = useCase(spy).execute("EMP002", "BD-G02", Direction.OUT);

        assertThat(stateWhenCommandWasSent).containsExactly("1:PENDING:NOT_ATTEMPTED");
        assertThat(result.syncStatus()).isEqualTo(SyncStatus.PENDING);
        assertThat(result.gateCommandStatus()).isEqualTo(GateCommandStatus.SENT);
        assertThat(result.employeeId()).isEqualTo("EMP002");
        assertThat(result.gateId()).isEqualTo("BD-G02");
        assertThat(result.direction()).isEqualTo(Direction.OUT);
        assertThat(result.devFixture()).isTrue();
    }

    @Test
    void turnstileFailureKeepsTheEventPendingAndRecordsTheFailureHonestly() {
        LocalAttendanceEvent result = useCase(gateId -> new GateCommandResult(GateCommandStatus.FAILED, "unreachable"))
                .execute(null, null, null);

        assertThat(result.syncStatus()).as("the scan is still durably recorded").isEqualTo(SyncStatus.PENDING);
        assertThat(result.gateCommandStatus()).isEqualTo(GateCommandStatus.FAILED);
        assertThat(result.gateCommandDetail()).isEqualTo("unreachable");
        assertThat(repository.countByStatus().pending()).isEqualTo(1);
    }

    @Test
    void omittedArgumentsFallBackToKioskDefaults() {
        LocalAttendanceEvent result = useCase(gateId -> new GateCommandResult(GateCommandStatus.SENT, "ok")).execute(null, " ", null);

        assertThat(result.employeeId()).isEqualTo("EMP001");
        assertThat(result.gateId()).isEqualTo("BD-G01");
        assertThat(result.factoryId()).isEqualTo("BD");
        assertThat(result.direction()).isEqualTo(Direction.IN);
    }
}
