package vn.edu.hcmut.tmc.kiosk.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import vn.edu.hcmut.tmc.kiosk.application.usecase.CentralSyncSwitch;
import vn.edu.hcmut.tmc.kiosk.application.usecase.SyncPendingEventsUseCase;
import vn.edu.hcmut.tmc.kiosk.application.usecase.SyncRunReport;
import vn.edu.hcmut.tmc.kiosk.application.usecase.SyncSettings;
import vn.edu.hcmut.tmc.kiosk.domain.exception.CentralUnavailableException;
import vn.edu.hcmut.tmc.kiosk.domain.model.Direction;
import vn.edu.hcmut.tmc.kiosk.domain.model.EventSyncResult;
import vn.edu.hcmut.tmc.kiosk.domain.model.KioskIdentity;
import vn.edu.hcmut.tmc.kiosk.domain.model.LocalAttendanceEvent;
import vn.edu.hcmut.tmc.kiosk.domain.model.QueueCounts;
import vn.edu.hcmut.tmc.kiosk.domain.model.ScanResult;
import vn.edu.hcmut.tmc.kiosk.domain.model.SyncOutcome;
import vn.edu.hcmut.tmc.kiosk.domain.model.SyncStatus;
import vn.edu.hcmut.tmc.kiosk.domain.port.IKioskSyncAPI;
import vn.edu.hcmut.tmc.kiosk.domain.service.RetryBackoffPolicy;
import vn.edu.hcmut.tmc.kiosk.infrastructure.persistence.SQLiteAttendanceRepository;
import vn.edu.hcmut.tmc.kiosk.infrastructure.persistence.SqliteConfig;

/** Sync state machine against a real SQLite queue; only the central side is scripted. */
class SyncPendingEventsUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-11-01T01:00:00Z");

    @TempDir
    Path dir;

    private SQLiteAttendanceRepository repository;
    private final CentralSyncSwitch centralSync = new CentralSyncSwitch(false);
    private final List<List<UUID>> idsSentToCentral = new ArrayList<>();

    @BeforeEach
    void setUp() {
        repository = new SQLiteAttendanceRepository(new JdbcTemplate(SqliteConfig.createDataSource(dir.resolve("kiosk.db"))));
    }

    private UUID queued(int secondsAfterT0) {
        UUID id = UUID.randomUUID();
        Instant at = NOW.minusSeconds(100).plusSeconds(secondsAfterT0);
        repository.insertPending(LocalAttendanceEvent.newPending(id, "EMP001", "BD", "BD-G01", Direction.IN, at, ScanResult.ALLOWED, true, at));
        return id;
    }

    private IKioskSyncAPI central(Function<List<LocalAttendanceEvent>, List<EventSyncResult>> behaviour) {
        return (kioskId, events) -> {
            idsSentToCentral.add(events.stream().map(LocalAttendanceEvent::attendanceId).toList());
            return behaviour.apply(events);
        };
    }

    private SyncPendingEventsUseCase useCase(IKioskSyncAPI api, Instant clockAt) {
        return new SyncPendingEventsUseCase(repository, api, new RetryBackoffPolicy(Duration.ofSeconds(2), Duration.ofSeconds(60)),
                centralSync, new KioskIdentity("BD-K01", "BD", "BD-G01"), new SyncSettings(20), Clock.fixed(clockAt, ZoneOffset.UTC));
    }

    private static EventSyncResult result(UUID id, SyncOutcome outcome) {
        return new EventSyncResult(id, outcome, outcome == SyncOutcome.STORED || outcome == SyncOutcome.ALREADY_EXISTS ? null : outcome.name());
    }

    @Test
    void storedAndAlreadyExistsBothMarkTheEventSynced() {
        UUID a = queued(0);
        UUID b = queued(1);

        SyncRunReport report = useCase(central(events -> List.of(result(a, SyncOutcome.STORED), result(b, SyncOutcome.ALREADY_EXISTS))), NOW).run();

        assertThat(report.synced()).isEqualTo(2);
        assertThat(repository.countByStatus()).isEqualTo(new QueueCounts(0, 2, 0));
        assertThat(repository.findById(a).orElseThrow().syncedAt()).isEqualTo(NOW);
    }

    @Test
    void onlyIdsNamedInTheAckChangeState() {
        UUID acked = queued(0);
        UUID silent = queued(1);
        UUID retryable = queued(2);
        UUID rejected = queued(3);

        SyncRunReport report = useCase(central(events -> List.of(
                result(acked, SyncOutcome.STORED),
                result(retryable, SyncOutcome.RETRYABLE),
                new EventSyncResult(rejected, SyncOutcome.REJECTED_PERMANENT, "schema violation"))), NOW).run();

        assertThat(report.attempted()).isEqualTo(4);
        assertThat(report.synced()).isEqualTo(1);
        assertThat(report.failed()).isEqualTo(1);
        assertThat(report.retryScheduled()).isEqualTo(2);
        assertThat(repository.findById(acked).orElseThrow().syncStatus()).isEqualTo(SyncStatus.SYNCED);
        assertThat(repository.findById(rejected).orElseThrow().syncStatus()).isEqualTo(SyncStatus.FAILED);
        assertThat(repository.findById(rejected).orElseThrow().lastError()).isEqualTo("schema violation");
        for (UUID stillPending : List.of(silent, retryable)) {
            LocalAttendanceEvent e = repository.findById(stillPending).orElseThrow();
            assertThat(e.syncStatus()).isEqualTo(SyncStatus.PENDING);
            assertThat(e.retryCount()).isEqualTo(1);
            assertThat(e.nextRetryAt()).isEqualTo(NOW.plusSeconds(2));
        }
    }

    @Test
    void wholeRequestFailureKeepsEverythingPendingAndTheRetryReusesTheSameIds() {
        UUID a = queued(0);
        UUID b = queued(1);

        // 1) the request fails (this models "central committed, then the response was lost" just as well as "central down")
        SyncRunReport failed = useCase(central(events -> {
            throw new CentralUnavailableException("timeout");
        }), NOW).run();
        assertThat(failed.skippedReason()).isEqualTo("CENTRAL_UNAVAILABLE");
        assertThat(repository.countByStatus()).isEqualTo(new QueueCounts(2, 0, 0));

        // 2) before the backoff expires nothing is even attempted
        SyncRunReport tooEarly = useCase(central(events -> List.of()), NOW.plusSeconds(1)).run();
        assertThat(tooEarly.attempted()).isZero();

        // 3) once due, the SAME ids go out again; central says it already has them -> SYNCED, no duplicates
        SyncRunReport recovered = useCase(central(events -> events.stream().map(e -> result(e.attendanceId(), SyncOutcome.ALREADY_EXISTS)).toList()),
                NOW.plusSeconds(3)).run();
        assertThat(recovered.synced()).isEqualTo(2);
        assertThat(repository.countByStatus()).isEqualTo(new QueueCounts(0, 2, 0));
        assertThat(idsSentToCentral).hasSize(2);
        assertThat(idsSentToCentral.get(1)).containsExactlyElementsOf(idsSentToCentral.get(0)).containsExactly(a, b);
    }

    @Test
    void backoffGrowsWithEachFailedAttempt() {
        UUID id = queued(0);
        IKioskSyncAPI down = central(events -> {
            throw new CentralUnavailableException("down");
        });

        useCase(down, NOW).run();
        assertThat(repository.findById(id).orElseThrow().nextRetryAt()).isEqualTo(NOW.plusSeconds(2));

        useCase(down, NOW.plusSeconds(10)).run();
        LocalAttendanceEvent second = repository.findById(id).orElseThrow();
        assertThat(second.retryCount()).isEqualTo(2);
        assertThat(second.nextRetryAt()).isEqualTo(NOW.plusSeconds(10).plusSeconds(4));
    }

    @Test
    void whileTheSwitchIsOffCentralIsNeverContactedAndNothingIsLost() {
        UUID id = queued(0);
        centralSync.setDisabled(true);

        SyncRunReport report = useCase(central(events -> {
            throw new AssertionError("central must not be called while sync is disabled");
        }), NOW).run();

        assertThat(report.skippedReason()).isEqualTo("CENTRAL_SYNC_DISABLED");
        assertThat(idsSentToCentral).isEmpty();
        assertThat(repository.findById(id).orElseThrow().syncStatus()).isEqualTo(SyncStatus.PENDING);
        assertThat(repository.findById(id).orElseThrow().retryCount()).as("being offline is not a failed attempt").isZero();
    }

    @Test
    void nothingIsSentWhenTheQueueIsEmpty() {
        SyncRunReport report = useCase(central(events -> List.of()), NOW).run();

        assertThat(report.attempted()).isZero();
        assertThat(idsSentToCentral).isEmpty();
    }
}
