package vn.edu.hcmut.tmc.kiosk.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import vn.edu.hcmut.tmc.kiosk.application.usecase.ProcessQrScanUseCase;
import vn.edu.hcmut.tmc.kiosk.domain.model.RejectedScan.Reason;
import vn.edu.hcmut.tmc.kiosk.domain.exception.WhitelistCacheException;
import vn.edu.hcmut.tmc.kiosk.domain.model.Direction;
import vn.edu.hcmut.tmc.kiosk.domain.model.GateCommandResult;
import vn.edu.hcmut.tmc.kiosk.domain.model.GateCommandStatus;
import vn.edu.hcmut.tmc.kiosk.domain.model.KioskIdentity;
import vn.edu.hcmut.tmc.kiosk.domain.model.LocalAttendanceEvent;
import vn.edu.hcmut.tmc.kiosk.domain.model.QrStatus;
import vn.edu.hcmut.tmc.kiosk.domain.model.ScanResult;
import vn.edu.hcmut.tmc.kiosk.domain.model.SyncStatus;
import vn.edu.hcmut.tmc.kiosk.domain.model.WhitelistEmployee;
import vn.edu.hcmut.tmc.kiosk.domain.model.WhitelistMetadata;
import vn.edu.hcmut.tmc.kiosk.domain.model.WhitelistSnapshot;
import vn.edu.hcmut.tmc.kiosk.domain.service.TotpValidatorDomainService;
import vn.edu.hcmut.tmc.kiosk.infrastructure.persistence.SQLiteAttendanceRepository;
import vn.edu.hcmut.tmc.kiosk.infrastructure.persistence.SQLiteWhitelistRepository;
import vn.edu.hcmut.tmc.kiosk.infrastructure.persistence.SqliteConfig;
import vn.edu.hcmut.tmc.kiosk.infrastructure.security.JdkHmacSha256;
import vn.edu.hcmut.tmc.kiosk.infrastructure.security.WhitelistSecretCodec;
import vn.edu.hcmut.tmc.kiosk.presentation.controller.KioskScanController;

class ProcessQrScanUseCaseTest {
    private static final Instant NOW = Instant.ofEpochSecond(59_830_000L * 30);
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final KioskIdentity IDENTITY = new KioskIdentity("BD-K01", "BD", "BD-G01");
    private static final byte[] SECRET = "TMC_LOAD_BD_1000_V1".getBytes(StandardCharsets.UTF_8);
    private static final String VALID_CODE = "727791";

    @TempDir
    Path directory;
    JdbcTemplate jdbc;
    SQLiteAttendanceRepository repository;
    SQLiteWhitelistRepository whitelist;
    AtomicInteger commands;
    ProcessQrScanUseCase scan;

    @BeforeEach
    void setup() {
        jdbc = new JdbcTemplate(SqliteConfig.createDataSource(directory.resolve("kiosk.db")));
        repository = new SQLiteAttendanceRepository(jdbc);
        String fixtureKey = java.util.Base64.getEncoder().encodeToString(new byte[32]);
        whitelist = new SQLiteWhitelistRepository(jdbc.getDataSource(), IDENTITY, WhitelistSecretCodec.fromBase64Key(fixtureKey));
        commands = new AtomicInteger();
        scan = scanner(repository);
    }

    @Test
    void commitsBothRowsBeforeGateAndRetainsGateFailureSeparately() {
        seed();
        var allowed = scan.execute("BD-G01", Direction.IN, "active", VALID_CODE, 1);
        assertThat(allowed.result()).isEqualTo(ScanResult.ALLOWED);
        assertThat(allowed.gateCommand()).isEqualTo(GateCommandStatus.FAILED);
        var saved = repository.findById(allowed.attendanceId()).orElseThrow();
        assertThat(saved.syncStatus()).isEqualTo(SyncStatus.PENDING);
        assertThat(saved.gateCommandStatus()).isEqualTo(GateCommandStatus.FAILED);
        assertThat(saved.devFixture()).isFalse();
        assertThat(commands.get()).isEqualTo(1);
    }

    @Test
    void webContractReturnsAllowedAndReplayedAs200WithoutEchoingTheCredential() throws Exception {
        seed();
        var mvc = MockMvcBuilders.standaloneSetup(new KioskScanController(scan)).build();
        // ts is deliberately different from the valid TOTP step.
        var request = post("/api/local/scan").contentType(MediaType.APPLICATION_JSON).content("""
                {"gateId":"BD-G01","direction":"IN","qrPayload":{"empId":"active","totp":"727791","ts":0,"ver":1}}
                """);
        var accepted = mvc.perform(request).andExpect(status().isOk())
                .andExpect(header().string("X-TMC-Dev-Only", "true"))
                .andExpect(jsonPath("$.result").value("ALLOWED"))
                .andExpect(jsonPath("$.gateCommand").value("FAILED"))
                .andExpect(jsonPath("$.attendanceId").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        var replayed = mvc.perform(request).andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("REJECTED"))
                .andExpect(jsonPath("$.reason").value("REPLAYED"))
                .andExpect(jsonPath("$.gateCommand").value("NOT_ATTEMPTED"))
                .andReturn().getResponse().getContentAsString();
        assertThat(accepted).doesNotContain(VALID_CODE);
        assertThat(replayed).doesNotContain(VALID_CODE);
    }

    @Test
    void repeatedAndConcurrentScansConsumeExactlyOneTokenEvenAfterReopening() throws Exception {
        seed();
        try (var threads = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var first = threads.submit(() -> {
                start.await();
                return scan.execute("BD-G01", Direction.IN, "active", VALID_CODE, 1);
            });
            var second = threads.submit(() -> {
                start.await();
                return scan.execute("BD-G01", Direction.IN, "active", VALID_CODE, 1);
            });
            start.countDown();
            var outcomes = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertThat(outcomes).extracting(ProcessQrScanUseCase.Outcome::result)
                    .containsExactlyInAnyOrder(ScanResult.ALLOWED, ScanResult.REJECTED);
            assertThat(outcomes).filteredOn(outcome -> outcome.result() == ScanResult.REJECTED)
                    .singleElement().extracting(ProcessQrScanUseCase.Outcome::reason).isEqualTo(Reason.REPLAYED);
        }
        var reopened = new SQLiteAttendanceRepository(
                new JdbcTemplate(SqliteConfig.createDataSource(directory.resolve("kiosk.db"))));
        assertThat(scanner(reopened).execute("BD-G01", Direction.OUT, "active", VALID_CODE, 1).reason())
                .isEqualTo(Reason.REPLAYED);
        assertThat(commands.get()).isEqualTo(1);
        assertThat(tokenCount()).isEqualTo(1);
        assertThat(reopened.findRecent(10)).hasSize(1);
        assertThat(jdbc.queryForList("SELECT reason FROM rejected_scan_audit", String.class))
                .containsExactly("REPLAYED", "REPLAYED");
    }

    @Test
    void rollsBackTokenIfPendingEventCannotBeWritten() {
        seed();
        repository.insertPending(LocalAttendanceEvent.newPending(UUID.randomUUID(), "active", "BD", "BD-G01",
                Direction.IN, NOW, ScanResult.ALLOWED, false, NOW));
        jdbc.execute("""
                CREATE TRIGGER fail_event BEFORE INSERT ON local_attendance_events
                BEGIN SELECT RAISE(ABORT, 'fixture write failure'); END
                """);

        assertThatThrownBy(() -> scan.execute("BD-G01", Direction.IN, "active", VALID_CODE, 1))
                .isInstanceOf(DataAccessException.class);
        assertThat(tokenCount()).isZero();
        assertThat(repository.findRecent(10)).hasSize(1);
        assertThat(commands.get()).isZero();
    }

    @Test
    void absentSnapshotAndRejectedScansDoNotConsumeTokensOrCallGate() {
        assertThatThrownBy(() -> scan.execute("BD-G01", Direction.IN, "active", VALID_CODE, 1))
                .isInstanceOf(WhitelistCacheException.class);
        seed();
        assertThat(scan.execute("BD-G01", Direction.IN, "unknown", VALID_CODE, 1).reason()).isEqualTo(Reason.UNKNOWN_EMPLOYEE);
        assertThat(scan.execute("BD-G01", Direction.IN, "revoked", VALID_CODE, 1).reason()).isEqualTo(Reason.REVOKED);
        assertThat(scan.execute("BD-G01", Direction.IN, "active", VALID_CODE, 2).reason()).isEqualTo(Reason.INVALID_TOTP);
        assertThat(scan.execute("BD-G01", Direction.IN, "no-material", VALID_CODE, 1).reason()).isEqualTo(Reason.INVALID_TOTP);
        assertThat(tokenCount()).isZero();
        assertThat(repository.findRecent(10)).isEmpty();
        assertThat(commands.get()).isZero();
        assertThat(jdbc.queryForList("SELECT reason FROM rejected_scan_audit", String.class))
                .containsExactlyInAnyOrder("UNKNOWN_EMPLOYEE", "REVOKED", "INVALID_TOTP", "INVALID_TOTP");
    }

    private ProcessQrScanUseCase scanner(SQLiteAttendanceRepository local) {
        return new ProcessQrScanUseCase(whitelist, new TotpValidatorDomainService(CLOCK, new JdkHmacSha256()),
                local, gate -> {
                    assertThat(local.findRecent(1)).singleElement().satisfies(event -> {
                        assertThat(event.syncStatus()).isEqualTo(SyncStatus.PENDING);
                        assertThat(event.gateCommandStatus()).isEqualTo(GateCommandStatus.NOT_ATTEMPTED);
                    });
                    assertThat(tokenCount()).isEqualTo(1);
                    commands.incrementAndGet();
                    return new GateCommandResult(GateCommandStatus.FAILED, "fixture gate failure");
                }, IDENTITY, CLOCK);
    }

    private int tokenCount() {
        return jdbc.queryForObject("SELECT count(*) FROM used_tokens", Integer.class);
    }

    private void seed() {
        var employees = List.of(new WhitelistEmployee("active", QrStatus.ACTIVE, 1, SECRET),
                new WhitelistEmployee("revoked", QrStatus.REVOKED, 1, SECRET),
                new WhitelistEmployee("no-material", QrStatus.ACTIVE, 1, null));
        whitelist.replaceIfNewer(new WhitelistSnapshot(
                new WhitelistMetadata("BD-K01", "BD", 1, NOW, employees.size()), employees));
    }
}
