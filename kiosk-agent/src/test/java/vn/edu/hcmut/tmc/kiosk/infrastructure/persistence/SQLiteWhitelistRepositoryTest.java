package vn.edu.hcmut.tmc.kiosk.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import vn.edu.hcmut.tmc.kiosk.domain.exception.WhitelistCacheException;
import vn.edu.hcmut.tmc.kiosk.domain.exception.WhitelistCacheException.Reason;
import vn.edu.hcmut.tmc.kiosk.domain.model.Direction;
import vn.edu.hcmut.tmc.kiosk.domain.model.KioskIdentity;
import vn.edu.hcmut.tmc.kiosk.domain.model.LocalAttendanceEvent;
import vn.edu.hcmut.tmc.kiosk.domain.model.QrStatus;
import vn.edu.hcmut.tmc.kiosk.domain.model.ScanResult;
import vn.edu.hcmut.tmc.kiosk.domain.model.WhitelistEmployee;
import vn.edu.hcmut.tmc.kiosk.domain.model.WhitelistLookup;
import vn.edu.hcmut.tmc.kiosk.domain.model.WhitelistMetadata;
import vn.edu.hcmut.tmc.kiosk.domain.model.WhitelistSnapshot;

/** Real SQLite file throughout; JDBC decorators only coordinate races or inject a failure at a precise boundary. */
class SQLiteWhitelistRepositoryTest {

    private static final Instant T0 = Instant.parse("2026-11-14T01:00:00.123Z");
    private static final KioskIdentity IDENTITY = new KioskIdentity("BD-K01", "BD", "BD-G01");

    @TempDir
    Path dir;

    private Path file;
    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private SQLiteWhitelistRepository repository;

    @BeforeEach
    void open() {
        file = dir.resolve("kiosk.db");
        dataSource = SqliteConfig.createDataSource(file);
        jdbc = new JdbcTemplate(dataSource);
        repository = new SQLiteWhitelistRepository(dataSource, IDENTITY);
    }

    private static WhitelistEmployee employee(String id, QrStatus status) {
        return new WhitelistEmployee(id, status, 1, null);
    }

    private static WhitelistSnapshot snapshot(long version, WhitelistEmployee... employees) {
        return new WhitelistSnapshot(new WhitelistMetadata("BD-K01", "BD", version, T0.plusSeconds(version), employees.length),
                List.of(employees));
    }

    private Map<String, Object> metadataRows() {
        return jdbc.query("SELECT key, value FROM kiosk_metadata ORDER BY key", rows -> {
            Map<String, Object> values = new java.util.LinkedHashMap<>();
            while (rows.next()) {
                values.put(rows.getString(1), rows.getString(2));
            }
            return values;
        });
    }

    private List<Map<String, Object>> employeeRows() {
        return jdbc.queryForList("SELECT employee_id, qr_status, secret_version, cache_version, cached_at FROM whitelist_cache ORDER BY employee_id");
    }

    private void assertCacheFailure(Runnable action, Reason reason) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(WhitelistCacheException.class, failure -> {
            assertThat(failure.reason()).isEqualTo(reason);
            assertThat(failure.getCause()).isNull();
            assertThat(failure.getMessage()).isIn("whitelist cache unavailable", "whitelist cache is inconsistent");
        });
    }

    @Test
    void noSnapshotIsUninitializedEvenWhenUnrelatedMetadataExists() {
        jdbc.update("INSERT INTO kiosk_metadata (key, value) VALUES ('clock_health', 'healthy')");

        assertThat(repository.inspect()).isEmpty();
        assertThat(repository.lookup("EMP001")).isEqualTo(WhitelistLookup.uninitialized());
        assertThat(metadataRows()).containsEntry("clock_health", "healthy");
    }

    @Test
    void storesAndReadsAllExistingQrStatusesWithAbsentMaterialAndVersionZero() {
        WhitelistSnapshot initial = snapshot(0, employee("EMP001", QrStatus.ACTIVE),
                employee("EMP002", QrStatus.REVOKED), employee("EMP003", QrStatus.DISABLED));

        assertThat(repository.replaceIfNewer(initial)).isTrue();
        assertThat(repository.inspect()).contains(initial.metadata());
        for (WhitelistEmployee employee : initial.employees()) {
            assertThat(repository.lookup(" " + employee.employeeId() + " "))
                    .isEqualTo(WhitelistLookup.found(initial.metadata(), employee));
        }
        assertThat(repository.lookup("UNKNOWN")).isEqualTo(WhitelistLookup.notFound(initial.metadata()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM whitelist_cache WHERE shared_secret_enc IS NOT NULL", Integer.class)).isZero();
        assertThat(metadataRows()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "whitelist.kioskId", "BD-K01", "whitelist.factoryId", "BD", "whitelist.version", "0",
                "whitelist.cachedAt", "2026-11-14T01:00:00.123Z", "whitelist.employeeCount", "3"));
    }

    @Test
    void newerSnapshotReplacesRatherThanMergesEmployeesAndUpdatesMetadata() {
        repository.replaceIfNewer(snapshot(1, employee("OLD", QrStatus.ACTIVE), employee("EMP001", QrStatus.ACTIVE)));
        WhitelistSnapshot newer = snapshot(2, employee("EMP001", QrStatus.REVOKED));

        assertThat(repository.replaceIfNewer(newer)).isTrue();
        assertThat(repository.inspect()).contains(newer.metadata());
        assertThat(repository.lookup("OLD")).isEqualTo(WhitelistLookup.notFound(newer.metadata()));
        assertThat(repository.lookup("EMP001")).isEqualTo(WhitelistLookup.found(newer.metadata(), newer.employees().getFirst()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM whitelist_cache", Integer.class)).isEqualTo(1);
    }

    @Test
    void equalAndOlderVersionsCannotChangeRowsOrRefreshTheCachedTimestamp() {
        WhitelistSnapshot current = snapshot(3, employee("EMP001", QrStatus.ACTIVE));
        repository.replaceIfNewer(current);
        var metadataBefore = metadataRows();
        var employeesBefore = employeeRows();

        assertThat(repository.replaceIfNewer(snapshot(2, employee("DIFFERENT", QrStatus.ACTIVE)))).isFalse();
        assertThat(repository.replaceIfNewer(snapshot(3))).isFalse();

        assertThat(metadataRows()).isEqualTo(metadataBefore);
        assertThat(employeeRows()).isEqualTo(employeesBefore);
    }

    @Test
    void emptySnapshotsInitializeTheCacheAndCanRemoveAllPreviousEmployees() {
        WhitelistSnapshot empty = snapshot(0);
        assertThat(repository.replaceIfNewer(empty)).isTrue();
        assertThat(repository.lookup("EMP001")).isEqualTo(WhitelistLookup.notFound(empty.metadata()));

        repository.replaceIfNewer(snapshot(1, employee("EMP001", QrStatus.ACTIVE)));
        WhitelistSnapshot cleared = snapshot(2);
        assertThat(repository.replaceIfNewer(cleared)).isTrue();

        assertThat(repository.inspect()).contains(cleared.metadata());
        assertThat(repository.lookup("EMP001").isInitialized()).isTrue();
        assertThat(repository.lookup("EMP001").employee()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM whitelist_cache", Integer.class)).isZero();
        assertThat(metadataRows()).containsEntry("whitelist.version", "2").containsEntry("whitelist.employeeCount", "0");
    }

    @Test
    void rejectsNullSnapshotInvalidLookupIdsAndIncomingScopeMismatchesWithoutChangingData() {
        repository.replaceIfNewer(snapshot(1, employee("EMP001", QrStatus.ACTIVE)));
        var before = metadataRows();

        assertThatThrownBy(() -> repository.replaceIfNewer(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.lookup(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.lookup(" \t")).isInstanceOf(IllegalArgumentException.class);
        for (WhitelistMetadata metadata : List.of(
                new WhitelistMetadata("OTHER", "BD", 2, T0, 0),
                new WhitelistMetadata("BD-K01", "OTHER", 2, T0, 0))) {
            assertThatThrownBy(() -> repository.replaceIfNewer(new WhitelistSnapshot(metadata, List.of())))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(metadataRows()).isEqualTo(before);
        assertThat(repository.lookup("EMP001").employee()).contains(employee("EMP001", QrStatus.ACTIVE));
    }

    @Test
    void refusesCacheBelongingToAnotherConfiguredKioskInsteadOfRescopingIt() {
        repository.replaceIfNewer(snapshot(1, employee("EMP001", QrStatus.ACTIVE)));
        var before = metadataRows();
        SQLiteWhitelistRepository otherKiosk = new SQLiteWhitelistRepository(dataSource, new KioskIdentity("OTHER", "BD", "GATE"));

        assertCacheFailure(otherKiosk::inspect, Reason.INVALID_CACHE);
        assertCacheFailure(() -> otherKiosk.lookup("EMP001"), Reason.INVALID_CACHE);
        assertCacheFailure(() -> otherKiosk.replaceIfNewer(new WhitelistSnapshot(
                new WhitelistMetadata("OTHER", "BD", 2, T0, 0), List.of())), Reason.INVALID_CACHE);
        assertThat(metadataRows()).isEqualTo(before);
    }

    @Test
    void partialMetadataAndOrphanEmployeesAreNotAnUninitializedCache() {
        jdbc.update("INSERT INTO kiosk_metadata (key, value) VALUES ('whitelist.version', '1')");
        assertCacheFailure(repository::inspect, Reason.INVALID_CACHE);
        assertCacheFailure(() -> repository.lookup("EMP001"), Reason.INVALID_CACHE);
        assertCacheFailure(() -> repository.replaceIfNewer(snapshot(2)), Reason.INVALID_CACHE);
        assertThat(metadataRows()).containsExactlyInAnyOrderEntriesOf(Map.of("whitelist.version", "1"));

        jdbc.update("DELETE FROM kiosk_metadata WHERE key = 'whitelist.version'");
        jdbc.update("""
                INSERT INTO whitelist_cache (employee_id, qr_status, secret_version, cache_version, cached_at)
                VALUES ('EMP001', 'ACTIVE', 1, 0, ?)
                """, T0.toString());
        assertCacheFailure(repository::inspect, Reason.INVALID_CACHE);
        assertCacheFailure(() -> repository.lookup("UNKNOWN"), Reason.INVALID_CACHE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"qr_status = 'UNKNOWN'", "secret_version = 0", "cache_version = 2",
            "cached_at = 'bad-timestamp'", "cached_at = '2026-11-14T02:00:00Z'", "employee_id = ' '"})
    void corruptEmployeeRowsAreReportedEvenForAnUnrelatedLookup(String corruption) {
        repository.replaceIfNewer(snapshot(1, employee("EMP001", QrStatus.ACTIVE)));
        jdbc.update("UPDATE whitelist_cache SET " + corruption);

        assertCacheFailure(repository::inspect, Reason.INVALID_CACHE);
        assertCacheFailure(() -> repository.lookup("UNKNOWN"), Reason.INVALID_CACHE);
        assertCacheFailure(() -> repository.replaceIfNewer(snapshot(2)), Reason.INVALID_CACHE);
    }

    static Stream<Arguments> corruptMetadata() {
        return Stream.of(Arguments.of("whitelist.version", "not-a-number"),
                Arguments.of("whitelist.version", "-1"), Arguments.of("whitelist.employeeCount", "2"),
                Arguments.of("whitelist.cachedAt", "not-an-instant"),
                Arguments.of("whitelist.cachedAt", "2026-11-14T01:00:01.123456Z"),
                Arguments.of("whitelist.kioskId", "OTHER"), Arguments.of("whitelist.factoryId", "OTHER"));
    }

    @ParameterizedTest
    @MethodSource("corruptMetadata")
    void corruptMetadataIsNotSilentlyNormalizedOrRepaired(String key, String value) {
        repository.replaceIfNewer(snapshot(1, employee("EMP001", QrStatus.ACTIVE)));
        jdbc.update("UPDATE kiosk_metadata SET value = ? WHERE key = ?", value, key);
        var before = metadataRows();

        assertCacheFailure(repository::inspect, Reason.INVALID_CACHE);
        assertCacheFailure(() -> repository.lookup("EMP001"), Reason.INVALID_CACHE);
        assertCacheFailure(() -> repository.replaceIfNewer(snapshot(2)), Reason.INVALID_CACHE);
        assertThat(metadataRows()).isEqualTo(before);
    }

    @Test
    void rejectsMaterialBeforeWritingAndNeverSilentlyDiscardsIt() {
        WhitelistSnapshot withMaterial = snapshot(2, new WhitelistEmployee("EMP001", QrStatus.ACTIVE, 1, new byte[] {9, 8, 7}));
        assertCacheFailure(() -> repository.replaceIfNewer(withMaterial), Reason.UNAVAILABLE);
        assertThat(repository.inspect()).isEmpty();
        assertThat(metadataRows()).isEmpty();

        repository.replaceIfNewer(snapshot(1, employee("OLD", QrStatus.ACTIVE)));
        var before = metadataRows();
        assertCacheFailure(() -> repository.replaceIfNewer(withMaterial), Reason.UNAVAILABLE);
        assertThat(metadataRows()).isEqualTo(before);
        assertThat(repository.lookup("OLD").employee()).isPresent();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM whitelist_cache WHERE shared_secret_enc IS NOT NULL", Integer.class)).isZero();
    }

    @Test
    void refusesExistingOpaqueBlobsUntilARealCodecIsAvailableWithoutReadingOrReplacingThem() {
        repository.replaceIfNewer(snapshot(1, employee("EMP001", QrStatus.ACTIVE)));
        byte[] opaqueFixture = {0, 11, 22, 33};
        jdbc.update("UPDATE whitelist_cache SET shared_secret_enc = ?", opaqueFixture);
        var before = metadataRows();

        assertCacheFailure(repository::inspect, Reason.UNAVAILABLE);
        assertCacheFailure(() -> repository.lookup("UNKNOWN"), Reason.UNAVAILABLE);
        assertCacheFailure(() -> repository.replaceIfNewer(snapshot(2)), Reason.UNAVAILABLE);
        assertThat(metadataRows()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT shared_secret_enc FROM whitelist_cache", byte[].class)).containsExactly(opaqueFixture);
    }

    @ParameterizedTest
    @ValueSource(strings = {"metadata", "commit"})
    void rollsBackEmployeeReplacementAndMetadataWhenAnActualTransactionFails(String failurePoint) {
        repository.replaceIfNewer(snapshot(1, employee("OLD", QrStatus.ACTIVE)));
        var employeesBefore = employeeRows();
        var metadataBefore = metadataRows();
        AtomicInteger metadataWrites = new AtomicInteger();
        DataSource failing = intercepted(sql -> {
            boolean fail = failurePoint.equals("commit") ? sql.equals("COMMIT")
                    : sql.startsWith("INSERT INTO kiosk_metadata") && metadataWrites.incrementAndGet() == 3;
            if (fail) {
                throw new SQLException("synthetic driver diagnostic must not escape");
            }
        });

        assertCacheFailure(() -> new SQLiteWhitelistRepository(failing, IDENTITY)
                .replaceIfNewer(snapshot(2, employee("NEW", QrStatus.DISABLED))), Reason.UNAVAILABLE);

        assertThat(employeeRows()).isEqualTo(employeesBefore);
        assertThat(metadataRows()).isEqualTo(metadataBefore);
        assertThat(repository.lookup("OLD").employee()).isPresent();
        assertThat(repository.replaceIfNewer(snapshot(3))).as("a failed transaction must release its lock").isTrue();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void restoresBothEmptyAndNonEmptySnapshotsAfterReopeningTheSameFile(boolean empty) {
        WhitelistSnapshot saved = empty ? snapshot(2) : snapshot(2, employee("EMP001", QrStatus.DISABLED));
        repository.replaceIfNewer(saved);

        SQLiteWhitelistRepository reopened = new SQLiteWhitelistRepository(SqliteConfig.createDataSource(file), IDENTITY);

        assertThat(reopened.inspect()).contains(saved.metadata());
        assertThat(reopened.lookup("EMP001").isInitialized()).isTrue();
        assertThat(reopened.lookup("EMP001").employee()).isEqualTo(repository.lookup("EMP001").employee());
    }

    @Test
    void readerKeepsTheOldEmployeeAndMetadataWhenWriterCommitsBetweenItsSelects() throws Exception {
        WhitelistSnapshot old = snapshot(1, employee("EMP001", QrStatus.ACTIVE));
        repository.replaceIfNewer(old);
        CountDownLatch metadataRead = new CountDownLatch(1);
        CountDownLatch resumeReader = new CountDownLatch(1);
        SQLiteWhitelistRepository reader = new SQLiteWhitelistRepository(intercepted(sql -> {
            if (sql.startsWith("SELECT employee_id")) {
                metadataRead.countDown();
                await(resumeReader);
            }
        }), IDENTITY);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            var result = pool.submit(() -> reader.lookup("EMP001"));
            assertThat(metadataRead.await(10, TimeUnit.SECONDS)).isTrue();
            repository.replaceIfNewer(snapshot(2, employee("EMP001", QrStatus.REVOKED)));
            resumeReader.countDown();

            assertThat(result.get(15, TimeUnit.SECONDS)).isEqualTo(WhitelistLookup.found(old.metadata(), old.employees().getFirst()));
            assertThat(repository.lookup("EMP001").employee().orElseThrow().qrStatus()).isEqualTo(QrStatus.REVOKED);
        } finally {
            resumeReader.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void readersNeverSeeAnUncommittedReplacement() throws Exception {
        WhitelistSnapshot old = snapshot(1, employee("OLD", QrStatus.ACTIVE));
        repository.replaceIfNewer(old);
        CountDownLatch readyToCommit = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        SQLiteWhitelistRepository writer = new SQLiteWhitelistRepository(intercepted(sql -> {
            if (sql.equals("COMMIT")) {
                readyToCommit.countDown();
                await(allowCommit);
            }
        }), IDENTITY);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            var write = pool.submit(() -> writer.replaceIfNewer(snapshot(2, employee("NEW", QrStatus.ACTIVE))));
            assertThat(readyToCommit.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(repository.lookup("OLD")).isEqualTo(WhitelistLookup.found(old.metadata(), old.employees().getFirst()));
            assertThat(repository.lookup("NEW").employee()).isEmpty();
            allowCommit.countDown();

            assertThat(write.get(15, TimeUnit.SECONDS)).isTrue();
            assertThat(repository.lookup("OLD").employee()).isEmpty();
            assertThat(repository.lookup("NEW").employee()).isPresent();
        } finally {
            allowCommit.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void waitingWriterRechecksVersionAfterTheOtherWriterCommits() throws Exception {
        repository.replaceIfNewer(snapshot(1, employee("OLD", QrStatus.ACTIVE)));
        CountDownLatch newerReady = new CountDownLatch(1);
        CountDownLatch olderStarted = new CountDownLatch(1);
        CountDownLatch allowNewerCommit = new CountDownLatch(1);
        SQLiteWhitelistRepository newerWriter = new SQLiteWhitelistRepository(intercepted(sql -> {
            if (sql.equals("COMMIT")) {
                newerReady.countDown();
                await(allowNewerCommit);
            }
        }), IDENTITY);
        SQLiteWhitelistRepository olderWriter = new SQLiteWhitelistRepository(intercepted(sql -> {
            if (sql.equals("BEGIN IMMEDIATE")) {
                olderStarted.countDown();
            }
        }), IDENTITY);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            var newer = pool.submit(() -> newerWriter.replaceIfNewer(snapshot(3, employee("NEW", QrStatus.ACTIVE))));
            assertThat(newerReady.await(10, TimeUnit.SECONDS)).isTrue();
            var older = pool.submit(() -> olderWriter.replaceIfNewer(snapshot(2, employee("STALE", QrStatus.ACTIVE))));
            assertThat(olderStarted.await(10, TimeUnit.SECONDS)).isTrue();
            allowNewerCommit.countDown();

            assertThat(newer.get(15, TimeUnit.SECONDS)).isTrue();
            assertThat(older.get(15, TimeUnit.SECONDS)).isFalse();
            assertThat(repository.inspect().orElseThrow().version()).isEqualTo(3);
            assertThat(repository.lookup("NEW").employee()).isPresent();
            assertThat(repository.lookup("STALE").employee()).isEmpty();
        } finally {
            allowNewerCommit.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void leavesAttendanceAntiReplayAndUnrelatedMetadataUntouched() {
        SQLiteAttendanceRepository attendance = new SQLiteAttendanceRepository(jdbc);
        UUID eventId = UUID.randomUUID();
        attendance.insertPending(LocalAttendanceEvent.newPending(eventId, "EMP001", "BD", "BD-G01", Direction.IN,
                T0, ScanResult.ALLOWED, true, T0));
        jdbc.update("INSERT INTO used_tokens (employee_id, matched_time_step, used_at) VALUES ('EMP001', 42, ?)", T0.toString());
        jdbc.update("INSERT INTO kiosk_metadata (key, value) VALUES ('clock_health', 'healthy'), ('whitelist.futureField', 'keep')");
        var attendanceBefore = jdbc.queryForList("SELECT * FROM local_attendance_events");
        var tokensBefore = jdbc.queryForList("SELECT * FROM used_tokens");

        repository.replaceIfNewer(snapshot(1, employee("EMP001", QrStatus.ACTIVE)));
        repository.replaceIfNewer(snapshot(2));

        assertThat(jdbc.queryForList("SELECT * FROM local_attendance_events")).isEqualTo(attendanceBefore);
        assertThat(jdbc.queryForList("SELECT * FROM used_tokens")).isEqualTo(tokensBefore);
        assertThat(metadataRows()).containsEntry("clock_health", "healthy").containsEntry("whitelist.futureField", "keep");
        assertThat(attendance.findById(eventId)).isPresent();
    }

    private DataSource intercepted(SqlHook before) {
        return new DelegatingDataSource(dataSource) {
            @Override
            public Connection getConnection() throws SQLException {
                Connection actual = super.getConnection();
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                        (proxy, method, args) -> {
                            Object result = invoke(actual, method, args);
                            if (method.getName().equals("createStatement")) {
                                return statementProxy((Statement) result, null, before);
                            }
                            if (method.getName().equals("prepareStatement")) {
                                return statementProxy((PreparedStatement) result, (String) args[0], before);
                            }
                            return result;
                        });
            }
        };
    }

    private static Statement statementProxy(Statement actual, String preparedSql, SqlHook before) {
        Class<?> type = preparedSql == null ? Statement.class : PreparedStatement.class;
        return (Statement) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
            if (method.getName().startsWith("execute")) {
                String sql = preparedSql == null ? (String) args[0] : preparedSql;
                before.accept(sql);
            }
            return invoke(actual, method, args);
        });
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
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new SQLException("test coordination interrupted");
        }
    }

    @FunctionalInterface
    private interface SqlHook {
        void accept(String sql) throws SQLException;
    }
}
