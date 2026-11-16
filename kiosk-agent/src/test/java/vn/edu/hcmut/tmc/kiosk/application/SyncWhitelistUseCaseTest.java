package vn.edu.hcmut.tmc.kiosk.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import vn.edu.hcmut.tmc.kiosk.application.usecase.CentralSyncSwitch;
import vn.edu.hcmut.tmc.kiosk.application.usecase.SyncWhitelistUseCase;
import vn.edu.hcmut.tmc.kiosk.application.usecase.WhitelistSyncReport;
import vn.edu.hcmut.tmc.kiosk.application.usecase.WhitelistSyncReport.Status;
import vn.edu.hcmut.tmc.kiosk.domain.exception.CentralUnavailableException;
import vn.edu.hcmut.tmc.kiosk.domain.model.KioskIdentity;
import vn.edu.hcmut.tmc.kiosk.domain.model.QrStatus;
import vn.edu.hcmut.tmc.kiosk.domain.model.WhitelistEmployee;
import vn.edu.hcmut.tmc.kiosk.domain.model.WhitelistMetadata;
import vn.edu.hcmut.tmc.kiosk.domain.model.WhitelistSnapshot;
import vn.edu.hcmut.tmc.kiosk.domain.port.IWhitelistSnapshotSource;
import vn.edu.hcmut.tmc.kiosk.domain.port.IWhitelistSnapshotStore;
import vn.edu.hcmut.tmc.kiosk.infrastructure.persistence.SQLiteWhitelistRepository;
import vn.edu.hcmut.tmc.kiosk.infrastructure.persistence.SqliteConfig;
import vn.edu.hcmut.tmc.kiosk.infrastructure.security.WhitelistSecretCodec;

/** Script only Central; persistence, encryption, version comparison and rollback use real SQLite/JCE. */
class SyncWhitelistUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-11-14T03:00:00.123456789Z");
    private static final KioskIdentity IDENTITY = new KioskIdentity("BD-K01", "BD", "BD-G01");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final WhitelistSnapshot OLD = snapshot("BD-K01", "BD", 3, "OLD");
    private static final WhitelistSnapshot NEW = snapshot("BD-K01", "BD", 4, "NEW");

    @TempDir
    Path dir;

    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private WhitelistSecretCodec codec;
    private SQLiteWhitelistRepository repository;
    private byte[] originalEnvelope;
    private final CentralSyncSwitch centralSync = new CentralSyncSwitch(false);
    private final AtomicInteger fetchCalls = new AtomicInteger();
    private final AtomicInteger storeCalls = new AtomicInteger();
    private final AtomicBoolean fetching = new AtomicBoolean();

    @BeforeEach
    void open() {
        dataSource = SqliteConfig.createDataSource(dir.resolve("kiosk.db"));
        jdbc = new JdbcTemplate(dataSource);
        byte[] temporaryKey = new byte[32];
        new SecureRandom().nextBytes(temporaryKey);
        codec = WhitelistSecretCodec.fromBase64Key(Base64.getEncoder().encodeToString(temporaryKey));
        Arrays.fill(temporaryKey, (byte) 0);
        repository = new SQLiteWhitelistRepository(dataSource, IDENTITY, codec);
        repository.replaceIfNewer(OLD);
        originalEnvelope = jdbc.queryForObject("SELECT shared_secret_enc FROM whitelist_cache", byte[].class);
    }

    private static WhitelistSnapshot snapshot(String kioskId, String factoryId, long version, String... ids) {
        List<WhitelistEmployee> employees = Arrays.stream(ids)
                .map(id -> new WhitelistEmployee(id, QrStatus.ACTIVE, 1, new byte[] {2, 4, 6, 8})).toList();
        return new WhitelistSnapshot(new WhitelistMetadata(kioskId, factoryId, version,
                NOW.minusSeconds(86400), employees.size()), employees);
    }

    private SyncWhitelistUseCase useCase(IWhitelistSnapshotSource source) {
        return useCase(source, repository);
    }

    private SyncWhitelistUseCase useCase(IWhitelistSnapshotSource source, IWhitelistSnapshotStore store) {
        return new SyncWhitelistUseCase((kioskId, factoryId) -> {
            fetchCalls.incrementAndGet();
            assertThat(kioskId).isEqualTo("BD-K01");
            assertThat(factoryId).isEqualTo("BD");
            assertThat(fetching.compareAndSet(false, true)).isTrue();
            try {
                return source.fetchSnapshot(kioskId, factoryId);
            } finally {
                fetching.set(false);
            }
        }, snapshot -> {
            assertThat(fetching.get()).as("remote fetch must finish before entering persistence").isFalse();
            storeCalls.incrementAndGet();
            return store.replaceIfNewer(snapshot);
        }, centralSync, IDENTITY, CLOCK);
    }

    private void assertOldCache() {
        assertThat(repository.inspect()).contains(OLD.metadata());
        assertThat(repository.lookup("OLD").employee()).contains(OLD.employees().getFirst());
        assertThat(repository.lookup("NEW").employee()).isEmpty();
        byte[] envelope = jdbc.queryForObject("SELECT shared_secret_enc FROM whitelist_cache", byte[].class);
        assertThat(Arrays.equals(envelope, originalEnvelope)).isTrue();
    }

    private static void assertStatus(WhitelistSyncReport report, Status status) {
        assertThat(report.status()).isEqualTo(status);
        if (status != Status.UPDATED && status != Status.NOT_NEWER) {
            assertThat(report.snapshotVersion()).isEmpty();
        }
    }

    @Test
    void offlineNeverCallsCentralOrPersistenceAndOnlineCanRunLater() {
        SyncWhitelistUseCase useCase = useCase((kioskId, factoryId) -> NEW);
        centralSync.setDisabled(true);

        assertStatus(useCase.run(), Status.OFFLINE);
        assertThat(fetchCalls).hasValue(0);
        assertThat(storeCalls).hasValue(0);
        assertOldCache();

        centralSync.setDisabled(false);
        assertStatus(useCase.run(), Status.UPDATED);
    }

    @Test
    void onlineCommitsEncryptedSnapshotWithLocalClockTimestampAndCanReopenIt() {
        WhitelistSyncReport report = useCase((kioskId, factoryId) -> NEW).run();

        assertStatus(report, Status.UPDATED);
        assertThat(report.snapshotVersion()).hasValue(4);
        assertThat(fetchCalls).hasValue(1);
        assertThat(storeCalls).hasValue(1);
        SQLiteWhitelistRepository reopened = new SQLiteWhitelistRepository(
                SqliteConfig.createDataSource(dir.resolve("kiosk.db")), IDENTITY, codec);
        assertThat(reopened.inspect()).contains(new WhitelistMetadata("BD-K01", "BD", 4,
                NOW.truncatedTo(ChronoUnit.MILLIS), 1));
        assertThat(reopened.lookup("NEW").employee()).contains(NEW.employees().getFirst());
        assertThat(reopened.lookup("OLD").employee()).isEmpty();
        assertThat(NEW.metadata().cachedAt()).isEqualTo(NOW.minusSeconds(86400).truncatedTo(ChronoUnit.MILLIS));
    }

    @ParameterizedTest
    @ValueSource(longs = {2, 3})
    void olderAndEqualVersionsAreNotRefreshesAndLeaveAllCachedDataUnchanged(long version) {
        WhitelistSyncReport report = useCase((kioskId, factoryId) -> snapshot(kioskId, factoryId, version, "NEW")).run();

        assertStatus(report, Status.NOT_NEWER);
        assertThat(report.snapshotVersion()).hasValue(version);
        assertThat(storeCalls).hasValue(1);
        assertOldCache();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsWrongKioskOrFactoryBeforeCallingStore(boolean wrongFactory) {
        WhitelistSyncReport report = useCase((kioskId, factoryId) -> snapshot(
                wrongFactory ? kioskId : "OTHER", wrongFactory ? "OTHER" : factoryId, 4, "NEW")).run();

        assertStatus(report, Status.INVALID_SNAPSHOT);
        assertThat(storeCalls).hasValue(0);
        assertOldCache();
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "duplicate", "invalid-data"})
    void invalidSourceDataNeverChangesCacheOrExposesAdapterDiagnostics(String invalid) {
        WhitelistSyncReport report = useCase((kioskId, factoryId) -> switch (invalid) {
            case "null" -> null;
            case "duplicate" -> snapshot(kioskId, factoryId, 4, "NEW", "NEW");
            default -> throw new IllegalArgumentException("sensitive source diagnostic must not escape");
        }).run();

        assertStatus(report, Status.INVALID_SNAPSHOT);
        assertThat(report.toString()).doesNotContain("sensitive source diagnostic");
        assertThat(storeCalls).hasValue(0);
        assertOldCache();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void timeoutOrUnavailableCentralPreservesCacheAndReleasesGuardForNextPass(boolean timeout) {
        AtomicBoolean first = new AtomicBoolean(true);
        SyncWhitelistUseCase useCase = useCase((kioskId, factoryId) -> {
            if (first.getAndSet(false)) {
                throw new CentralUnavailableException("sensitive source diagnostic must not escape",
                        timeout ? new SocketTimeoutException() : new IllegalStateException());
            }
            return NEW;
        });

        WhitelistSyncReport report = useCase.run();
        assertStatus(report, Status.SOURCE_UNAVAILABLE);
        assertThat(report.toString()).doesNotContain("sensitive source diagnostic");
        assertThat(storeCalls).hasValue(0);
        assertOldCache();
        assertStatus(useCase.run(), Status.UPDATED);
    }

    @Test
    void goingOfflineDuringFetchDiscardsTheCandidateWithoutStartingStorage() {
        WhitelistSyncReport report = useCase((kioskId, factoryId) -> {
            centralSync.setDisabled(true);
            return NEW;
        }).run();

        assertStatus(report, Status.OFFLINE);
        assertThat(fetchCalls).hasValue(1);
        assertThat(storeCalls).hasValue(0);
        assertOldCache();
        centralSync.setDisabled(false);
        assertStatus(useCase((kioskId, factoryId) -> NEW).run(), Status.UPDATED);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void versionZeroInitializesAnUninitializedCacheIncludingAnEmptySnapshot(boolean empty) {
        SQLiteWhitelistRepository fresh = new SQLiteWhitelistRepository(
                SqliteConfig.createDataSource(dir.resolve("fresh.db")), IDENTITY, codec);
        WhitelistSnapshot initial = empty ? snapshot("BD-K01", "BD", 0) : snapshot("BD-K01", "BD", 0, "NEW");
        assertThat(fresh.inspect()).isEmpty();

        WhitelistSyncReport report = useCase((kioskId, factoryId) -> initial, fresh).run();

        assertStatus(report, Status.UPDATED);
        assertThat(report.snapshotVersion()).hasValue(0);
        assertThat(fresh.lookup("NEW").isInitialized()).isTrue();
        assertThat(fresh.inspect().orElseThrow().employeeCount()).isEqualTo(empty ? 0 : 1);
    }

    @Test
    void authoritativeEmptySnapshotClearsEmployeesButKeepsInitializedMetadata() {
        WhitelistSyncReport report = useCase((kioskId, factoryId) -> snapshot(kioskId, factoryId, 4)).run();

        assertStatus(report, Status.UPDATED);
        assertThat(repository.lookup("OLD").isInitialized()).isTrue();
        assertThat(repository.lookup("OLD").employee()).isEmpty();
        assertThat(repository.inspect().orElseThrow().employeeCount()).isZero();
    }

    @Test
    void aWriterDuringDownloadWinsAgainstTheOlderCandidateWithoutHoldingALocalWriteTransaction() {
        WhitelistSnapshot external = snapshot("BD-K01", "BD", 5, "EXTERNAL");
        WhitelistSyncReport report = useCase((kioskId, factoryId) -> {
            assertThat(repository.replaceIfNewer(external)).isTrue();
            return NEW;
        }).run();

        assertStatus(report, Status.NOT_NEWER);
        assertThat(report.snapshotVersion()).hasValue(4);
        assertThat(repository.inspect()).contains(external.metadata());
        assertThat(repository.lookup("EXTERNAL").employee()).contains(external.employees().getFirst());
        assertThat(repository.lookup("NEW").employee()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"fetch", "commit"})
    void concurrentRunsNeverOverlapAndSuccessWaitsForTheActualCommit(String phase) throws Exception {
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        IWhitelistSnapshotStore store = new SQLiteWhitelistRepository(intercepted(sql -> {
            if (phase.equals("commit") && sql.equals("COMMIT")) {
                reached.countDown();
                await(proceed);
            }
        }), IDENTITY, codec);
        SyncWhitelistUseCase useCase = useCase((kioskId, factoryId) -> {
            if (phase.equals("fetch")) {
                reached.countDown();
                try {
                    await(proceed);
                } catch (SQLException failure) {
                    throw new AssertionError(failure);
                }
            }
            return NEW;
        }, store);
        var pool = Executors.newSingleThreadExecutor();
        try {
            var first = pool.submit(useCase::run);
            assertThat(reached.await(10, TimeUnit.SECONDS)).isTrue();
            assertStatus(useCase.run(), Status.ALREADY_RUNNING);
            assertThat(first.isDone()).isFalse();
            assertThat(fetchCalls).hasValue(1);
            assertOldCache();
            proceed.countDown();

            assertStatus(first.get(15, TimeUnit.SECONDS), Status.UPDATED);
            assertStatus(useCase.run(), Status.NOT_NEWER);
        } finally {
            proceed.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void recursiveRunIsRejectedRatherThanReenteringTheSource() {
        AtomicReference<SyncWhitelistUseCase> useCase = new AtomicReference<>();
        useCase.set(useCase((kioskId, factoryId) -> {
            assertStatus(useCase.get().run(), Status.ALREADY_RUNNING);
            return NEW;
        }));

        assertStatus(useCase.get().run(), Status.UPDATED);
        assertThat(fetchCalls).hasValue(1);
    }

    @Test
    void commitFailureNeverReportsSuccessAndRollsBackEncryptedSnapshot() {
        IWhitelistSnapshotStore failing = new SQLiteWhitelistRepository(intercepted(sql -> {
            if (sql.equals("COMMIT")) {
                throw new SQLException("synthetic driver diagnostic must not escape");
            }
        }), IDENTITY, codec);

        WhitelistSyncReport report = useCase((kioskId, factoryId) -> NEW, failing).run();

        assertStatus(report, Status.CACHE_UNAVAILABLE);
        assertOldCache();
    }

    @Test
    void missingEncryptionKeyNeverReportsSuccessOrChangesAnExistingMaterialFreeSnapshot() {
        repository.replaceIfNewer(new WhitelistSnapshot(new WhitelistMetadata("BD-K01", "BD", 4, NOW, 0), List.of()));
        IWhitelistSnapshotStore unavailable = new SQLiteWhitelistRepository(dataSource, IDENTITY,
                WhitelistSecretCodec.fromBase64Key(null));

        WhitelistSyncReport report = useCase((kioskId, factoryId) -> snapshot(kioskId, factoryId, 5, "NEW"), unavailable).run();

        assertStatus(report, Status.CACHE_UNAVAILABLE);
        assertThat(repository.inspect().orElseThrow().version()).isEqualTo(4);
        assertThat(repository.lookup("NEW").employee()).isEmpty();
    }

    @Test
    void anInconsistentCacheIsReportedWithoutResettingOrRepairingIt() {
        jdbc.update("UPDATE kiosk_metadata SET value = '2' WHERE key = 'whitelist.employeeCount'");

        assertStatus(useCase((kioskId, factoryId) -> NEW).run(), Status.INVALID_CACHE);
        assertThat(jdbc.queryForObject("SELECT value FROM kiosk_metadata WHERE key = 'whitelist.employeeCount'", String.class)).isEqualTo("2");
        assertThat(jdbc.queryForObject("SELECT employee_id FROM whitelist_cache", String.class)).isEqualTo("OLD");
    }

    @Test
    void unexpectedProgrammingFailureReleasesGuardWithoutWritingOrHidingTheFailure() {
        AtomicBoolean first = new AtomicBoolean(true);
        SyncWhitelistUseCase useCase = useCase((kioskId, factoryId) -> {
            if (first.getAndSet(false)) {
                throw new IllegalStateException("synthetic programming error");
            }
            return NEW;
        });

        assertThatThrownBy(useCase::run).isInstanceOf(IllegalStateException.class);
        assertOldCache();
        assertStatus(useCase.run(), Status.UPDATED);
    }

    private DataSource intercepted(SqlHook before) {
        return new DelegatingDataSource(dataSource) {
            @Override
            public Connection getConnection() throws SQLException {
                Connection actual = super.getConnection();
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                        (proxy, method, args) -> {
                            Object result = invoke(actual, method, args);
                            if (!method.getName().equals("createStatement")) {
                                return result;
                            }
                            Statement statement = (Statement) result;
                            return Proxy.newProxyInstance(Statement.class.getClassLoader(), new Class<?>[] {Statement.class},
                                    (statementProxy, statementMethod, statementArgs) -> {
                                        if (statementMethod.getName().startsWith("execute")) {
                                            before.accept((String) statementArgs[0]);
                                        }
                                        return invoke(statement, statementMethod, statementArgs);
                                    });
                        });
            }
        };
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    private static void await(CountDownLatch latch) throws SQLException {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new SQLException("test coordination timed out");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new SQLException("test coordination interrupted");
        }
    }

    @FunctionalInterface
    private interface SqlHook {
        void accept(String sql) throws SQLException;
    }
}
