package vn.edu.hcmut.tmc.kiosk.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;

import vn.edu.hcmut.tmc.kiosk.KioskAgentApplication;
import vn.edu.hcmut.tmc.kiosk.application.usecase.CentralSyncSwitch;
import vn.edu.hcmut.tmc.kiosk.application.usecase.SyncWhitelistUseCase;
import vn.edu.hcmut.tmc.kiosk.application.usecase.WhitelistSyncReport.Status;
import vn.edu.hcmut.tmc.kiosk.domain.model.KioskIdentity;
import vn.edu.hcmut.tmc.kiosk.domain.model.QrStatus;
import vn.edu.hcmut.tmc.kiosk.domain.model.WhitelistEmployee;
import vn.edu.hcmut.tmc.kiosk.domain.model.WhitelistMetadata;
import vn.edu.hcmut.tmc.kiosk.domain.model.WhitelistSnapshot;
import vn.edu.hcmut.tmc.kiosk.domain.port.IWhitelistSnapshotSource;
import vn.edu.hcmut.tmc.kiosk.domain.port.IWhitelistSnapshotStore;
import vn.edu.hcmut.tmc.kiosk.infrastructure.config.WhitelistSecretConfig;
import vn.edu.hcmut.tmc.kiosk.infrastructure.persistence.SQLiteWhitelistRepository;
import vn.edu.hcmut.tmc.kiosk.infrastructure.persistence.SqliteConfig;
import vn.edu.hcmut.tmc.kiosk.infrastructure.security.WhitelistSecretCodec;
import vn.edu.hcmut.tmc.kiosk.infrastructure.sync.RestWhitelistSnapshotSource;
import vn.edu.hcmut.tmc.kiosk.presentation.controller.DevWhitelistSyncController;

/** Real Spring composition, SQLite and AES; only Central HTTP is mocked. All triggers are explicit test calls. */
@ExtendWith(OutputCaptureExtension.class)
class WhitelistHttpSyncWiringTest {

    private static final KioskIdentity IDENTITY = new KioskIdentity("BD-K01", "BD", "BD-G01");
    private static final Instant NOW = Instant.parse("2026-11-14T06:00:00.123Z");
    private static final String API_KEY = "synthetic-wired-http-key";
    private static final String SECRET = "  synthetic UTF-8 material Đ-🔐  ";
    private static final String URL = "http://central.invalid/api/v1/kiosk/whitelist?factoryId=BD";

    @TempDir
    Path dir;

    private DataSource database;
    private JdbcTemplate jdbc;
    private SQLiteWhitelistRepository stored;
    private MockRestServiceServer http;
    private ApplicationContextRunner context;
    private final CentralSyncSwitch centralSync = new CentralSyncSwitch(false);
    private final AtomicInteger transactions = new AtomicInteger();
    private final AtomicInteger writeBegins = new AtomicInteger();
    private SqlHook sqlHook = sql -> { };
    private Runnable afterCommit = () -> { };

    @BeforeEach
    void prepareRealPersistenceAndProductionComposition() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        String encodedKey = Base64.getEncoder().encodeToString(key);
        Arrays.fill(key, (byte) 0);
        database = SqliteConfig.createDataSource(dir.resolve("kiosk.db"));
        jdbc = new JdbcTemplate(database);
        stored = new SQLiteWhitelistRepository(database, IDENTITY, WhitelistSecretCodec.fromBase64Key(encodedKey));
        RestClient.Builder builder = RestClient.builder().baseUrl("http://central.invalid");
        http = MockRestServiceServer.bindTo(builder).build();
        RestClient client = builder.build();
        context = new ApplicationContextRunner()
                // Deliberately import only the composition root; it must include its real HTTP source definition.
                .withUserConfiguration(WhitelistSyncWiringConfig.class, WhitelistSecretConfig.class,
                        SQLiteWhitelistRepository.class)
                .withBean(DataSource.class, this::observedDatabase)
                .withBean("centralRestClient", RestClient.class, () -> client)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(KioskIdentity.class, () -> IDENTITY)
                .withBean(Clock.class, () -> Clock.fixed(NOW, ZoneOffset.UTC))
                .withBean(CentralSyncSwitch.class, () -> centralSync)
                // Park periodic execution here; this suite's other tests exercise explicit triggers.
                .withPropertyValues("kiosk.whitelist.sync-enabled=true", "kiosk.whitelist.sync-interval-ms=86400000",
                        "kiosk.whitelist.api-key=" + API_KEY,
                        "kiosk.whitelist.secret-key-base64=" + encodedKey);
    }

    @AfterEach
    void noLeaksOrUnclosedTransactions(CapturedOutput output) {
        http.verify();
        assertThat(transactions).hasValue(0);
        assertThat(output).doesNotContain(API_KEY, SECRET);
    }

    private WhitelistSnapshot oldSnapshot() {
        return new WhitelistSnapshot(new WhitelistMetadata("BD-K01", "BD", 4, NOW.minusSeconds(60), 1),
                List.of(new WhitelistEmployee("OLD", QrStatus.ACTIVE, 2, "synthetic-old".getBytes(StandardCharsets.UTF_8))));
    }

    private void seedOld() {
        assertThat(stored.replaceIfNewer(oldSnapshot())).isTrue();
    }

    private void assertOld() {
        assertThat(stored.inspect()).contains(oldSnapshot().metadata());
        assertThat(stored.lookup("OLD").employee()).contains(oldSnapshot().employees().getFirst());
        assertThat(stored.lookup("NEW").employee()).isEmpty();
    }

    private String employees() {
        return "[{\"employeeId\":\"NEW\",\"fullName\":\"Synthetic\",\"factoryId\":\"BD\","
                + "\"department\":\"Assembly\",\"qrStatus\":\"ACTIVE\",\"secretVersion\":3,"
                + "\"sharedSecret\":\"" + SECRET + "\",\"updatedAt\":\"2026-11-13T00:00:00Z\"}]";
    }

    private void expectSnapshot(String body, String revision) {
        var response = withSuccess(body, MediaType.APPLICATION_JSON);
        if (revision != null) {
            response.header("X-Whitelist-Version", revision);
        }
        http.expect(requestTo(URL)).andExpect(header("X-Kiosk-Api-Key", API_KEY)).andRespond(request -> {
            assertThat(transactions).as("HTTP must run outside SQLite transactions").hasValue(0);
            return response.createResponse(request);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "test"})
    void flagFalseLeavesCacheUnchangedAndCreatesNoSyncBeansInEitherProfile(String profile) {
        seedOld();
        context.withPropertyValues("spring.profiles.active=" + profile, "kiosk.whitelist.sync-enabled=false")
                .run(application -> {
                    assertThat(application).hasNotFailed().doesNotHaveBean(IWhitelistSnapshotSource.class)
                            .doesNotHaveBean(SyncWhitelistUseCase.class);
                    assertOld();
                    assertThat(writeBegins).hasValue(0);
                });
    }

    @Test
    void enabledFlagCreatesOneRealSourceAndSingletonButDoesNothingUntilExplicitlyTriggered() {
        context.run(application -> {
            assertThat(application).hasNotFailed().hasSingleBean(IWhitelistSnapshotSource.class)
                    .hasSingleBean(RestWhitelistSnapshotSource.class).hasSingleBean(SyncWhitelistUseCase.class);
            var first = application.getBean(SyncWhitelistUseCase.class);
            assertThat(first).isSameAs(application.getBean(SyncWhitelistUseCase.class));
            assertThat(application.getBeanFactory().isSingleton("syncWhitelistUseCase")).isTrue();
            assertThat(application.getBean(IWhitelistSnapshotStore.class)).isInstanceOf(SQLiteWhitelistRepository.class);
            assertThat(stored.inspect()).isEmpty();
            assertThat(writeBegins).hasValue(0);
            http.verify();
        });
    }

    @Test
    void sharedOfflineSwitchPreventsAnyHttpOrCacheWrite() {
        seedOld();
        centralSync.setDisabled(true);
        context.run(application -> {
            assertThat(application.getBean(CentralSyncSwitch.class)).isSameAs(centralSync);
            assertThat(application.getBean(SyncWhitelistUseCase.class).run().status()).isEqualTo(Status.OFFLINE);
            assertOld();
            assertThat(writeBegins).hasValue(0);
        });
    }

    @Test
    void explicitHttpTriggerCommitsEncryptedSnapshotAndReopenRecoversIt() {
        seedOld();
        expectSnapshot(employees(), "5");
        context.run(application -> {
            assertThat(application.getBean(SyncWhitelistUseCase.class).run().status()).isEqualTo(Status.UPDATED);
            assertThat(stored.inspect()).contains(new WhitelistMetadata("BD-K01", "BD", 5, NOW, 1));
            assertThat(stored.lookup("OLD").employee()).isEmpty();
            assertThat(stored.lookup("NEW").employee().orElseThrow().secretMaterial())
                    .isEqualTo(SECRET.getBytes(StandardCharsets.UTF_8));
            assertThat(jdbc.queryForObject("SELECT shared_secret_enc FROM whitelist_cache", byte[].class))
                    .isNotEmpty().isNotEqualTo(SECRET.getBytes(StandardCharsets.UTF_8));
            var reopened = new SQLiteWhitelistRepository(SqliteConfig.createDataSource(dir.resolve("kiosk.db")),
                    IDENTITY, application.getBean(WhitelistSecretCodec.class));
            assertThat(reopened.lookup("NEW")).isEqualTo(stored.lookup("NEW"));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"3", "4"})
    void olderOrEqualRevisionPreservesAllOldDataAndMetadata(String revision) {
        seedOld();
        byte[] ciphertext = jdbc.queryForObject("SELECT shared_secret_enc FROM whitelist_cache", byte[].class);
        expectSnapshot(employees(), revision);
        context.run(application -> {
            assertThat(application.getBean(SyncWhitelistUseCase.class).run().status()).isEqualTo(Status.NOT_NEWER);
            assertOld();
            assertThat(jdbc.queryForObject("SELECT shared_secret_enc FROM whitelist_cache", byte[].class))
                    .isEqualTo(ciphertext);
        });
    }

    @Test
    void validEmptySnapshotInitializesAnEmptyCacheAndCanReplaceAnExistingSnapshot() {
        expectSnapshot("[]", "1");
        expectSnapshot(employees(), "2");
        expectSnapshot("[]", "3");
        context.run(application -> {
            var useCase = application.getBean(SyncWhitelistUseCase.class);
            assertThat(stored.inspect()).isEmpty();
            assertThat(useCase.run().status()).isEqualTo(Status.UPDATED);
            assertThat(stored.inspect()).contains(new WhitelistMetadata("BD-K01", "BD", 1, NOW, 0));
            assertThat(useCase.run().status()).isEqualTo(Status.UPDATED);
            assertThat(stored.lookup("NEW").employee()).isPresent();
            assertThat(useCase.run().status()).isEqualTo(Status.UPDATED);
            assertThat(stored.inspect()).contains(new WhitelistMetadata("BD-K01", "BD", 3, NOW, 0));
            assertThat(stored.lookup("NEW").employee()).isEmpty();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"malformed", "missing-header", "wrong-factory", "invalid-version"})
    void invalidSnapshotNeverWritesOrClearsOldCache(String failure) {
        seedOld();
        String body = switch (failure) {
            case "malformed" -> "[";
            case "wrong-factory" -> employees().replace("\"BD\"", "\"LA\"");
            default -> employees();
        };
        String revision = switch (failure) {
            case "missing-header" -> null;
            case "invalid-version" -> "0";
            default -> "5";
        };
        expectSnapshot(body, revision);
        context.run(application -> {
            assertThat(application.getBean(SyncWhitelistUseCase.class).run().status()).isEqualTo(Status.INVALID_SNAPSHOT);
            assertOld();
            assertThat(writeBegins).hasValue(0);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"timeout", "403", "503", "501"})
    void httpFailureKeepsOldCacheAndDoesNotOpenAWriteTransaction(String failure) {
        seedOld();
        var request = http.expect(requestTo(URL));
        if (failure.equals("timeout")) {
            request.andRespond(withException(new SocketTimeoutException("synthetic timeout")));
        } else {
            request.andRespond(withStatus(org.springframework.http.HttpStatusCode.valueOf(Integer.parseInt(failure))));
        }
        context.run(application -> {
            assertThat(application.getBean(SyncWhitelistUseCase.class).run().status()).isEqualTo(Status.SOURCE_UNAVAILABLE);
            assertOld();
            assertThat(writeBegins).hasValue(0);
        });
    }

    @Test
    void enabledWithoutHttpKeyStillStartsAndFailsExplicitSyncBeforeAnyNetwork() {
        seedOld();
        context.withPropertyValues("kiosk.whitelist.api-key=").run(application -> {
            assertThat(application).hasNotFailed().hasSingleBean(SyncWhitelistUseCase.class);
            assertThat(application.getBean(SyncWhitelistUseCase.class).run().status()).isEqualTo(Status.SOURCE_UNAVAILABLE);
            assertOld();
            assertThat(writeBegins).hasValue(0);
        });
    }

    @Test
    void missingAesKeyRollsBackTheReplacementOfAnInitializedEmptyCache() {
        var empty = new WhitelistSnapshot(new WhitelistMetadata("BD-K01", "BD", 4, NOW.minusSeconds(60), 0), List.of());
        stored.replaceIfNewer(empty);
        expectSnapshot(employees(), "5");
        context.withPropertyValues("kiosk.whitelist.secret-key-base64=").run(application -> {
            assertThat(application).hasNotFailed();
            assertThat(application.getBean(SyncWhitelistUseCase.class).run().status()).isEqualTo(Status.CACHE_UNAVAILABLE);
            assertThat(stored.inspect()).contains(empty.metadata());
            assertThat(stored.lookup("NEW").employee()).isEmpty();
        });
    }

    @Test
    void commitFailureRollsBackAndNeverReportsUpdated() {
        seedOld();
        expectSnapshot(employees(), "5");
        AtomicBoolean failOnce = new AtomicBoolean(true);
        sqlHook = sql -> {
            if (sql.equals("COMMIT") && failOnce.getAndSet(false)) {
                throw new SQLException("synthetic commit failure");
            }
        };
        context.run(application -> {
            assertThat(application.getBean(SyncWhitelistUseCase.class).run().status()).isEqualTo(Status.CACHE_UNAVAILABLE);
            assertOld();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"fetch", "commit"})
    void concurrentConsumersShareTheSameGuardThroughFetchAndCommit(String phase) {
        seedOld();
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean blockOnce = new AtomicBoolean(true);
        if (phase.equals("commit")) {
            sqlHook = sql -> {
                if (sql.equals("COMMIT") && blockOnce.getAndSet(false)) {
                    reached.countDown();
                    await(release);
                }
            };
        }
        http.expect(requestTo(URL)).andRespond(request -> {
            assertThat(transactions).hasValue(0);
            if (phase.equals("fetch")) {
                reached.countDown();
                try {
                    await(release);
                } catch (SQLException failure) {
                    throw new java.io.IOException("test coordination failed");
                }
            }
            return withSuccess(employees(), MediaType.APPLICATION_JSON).header("X-Whitelist-Version", "5")
                    .createResponse(request);
        });
        context.run(application -> {
            var first = application.getBean(SyncWhitelistUseCase.class);
            var second = application.getBean(SyncWhitelistUseCase.class);
            assertThat(first).isSameAs(second);
            try (var worker = Executors.newSingleThreadExecutor()) {
                var running = worker.submit(first::run);
                try {
                    assertThat(reached.await(5, TimeUnit.SECONDS)).isTrue();
                    assertThat(running.isDone()).as("no success before commit").isFalse();
                    assertThat(second.run().status()).isEqualTo(Status.ALREADY_RUNNING);
                    assertOld();
                } finally {
                    release.countDown();
                }
                assertThat(running.get(5, TimeUnit.SECONDS).status()).isEqualTo(Status.UPDATED);
            }
        });
    }

    @Test
    void snapshotReplacementLeavesAttendanceQueueTokensAndOtherMetadataUntouched() {
        jdbc.update("""
                INSERT INTO local_attendance_events
                (attendance_id, employee_id, factory_id, gate_id, direction, occurred_at, scan_result, created_at)
                VALUES ('queue-event', 'EMP-Q', 'BD', 'BD-G01', 'IN', ?, 'ALLOWED', ?)
                """, NOW.toString(), NOW.toString());
        jdbc.update("INSERT INTO used_tokens VALUES ('EMP-Q', 123, ?)", NOW.toString());
        jdbc.update("INSERT INTO kiosk_metadata VALUES ('unrelated', 'keep')");
        expectSnapshot(employees(), "1");
        context.run(application -> {
            assertThat(application.getBean(SyncWhitelistUseCase.class).run().status()).isEqualTo(Status.UPDATED);
            assertThat(jdbc.queryForObject("SELECT sync_status FROM local_attendance_events", String.class)).isEqualTo("PENDING");
            assertThat(jdbc.queryForObject("SELECT matched_time_step FROM used_tokens", Long.class)).isEqualTo(123L);
            assertThat(jdbc.queryForObject("SELECT value FROM kiosk_metadata WHERE key = 'unrelated'", String.class)).isEqualTo("keep");
        });
    }

    @Test
    void scheduledHttpFailureKeepsCacheAndTheNextCycleCanCommit() {
        seedOld();
        CountDownLatch firstFailed = new CountDownLatch(1);
        CountDownLatch committed = new CountDownLatch(1);
        afterCommit = committed::countDown;
        http.expect(requestTo(URL)).andRespond(request -> {
            firstFailed.countDown();
            return withStatus(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE).createResponse(request);
        });
        expectSnapshot(employees(), "5");
        context.withPropertyValues("kiosk.whitelist.sync-interval-ms=1000").run(application -> {
            assertThat(firstFailed.await(5, TimeUnit.SECONDS)).isTrue();
            assertOld();
            assertThat(committed.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(stored.inspect()).contains(new WhitelistMetadata("BD-K01", "BD", 5, NOW, 1));
            assertThat(stored.lookup("NEW").employee()).isPresent();
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void actualComponentScanAndYamlHonorTheEnvironmentFlagWithoutStartupFetch(boolean enabled) {
        new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(KioskAgentApplication.class)
                .withPropertyValues("KIOSK_WHITELIST_SYNC_ENABLED=" + enabled, "kiosk.sync.enabled=false",
                        "kiosk.whitelist.sync-interval-ms=86400000",
                        "kiosk.sqlite.path=" + dir.resolve("whole-kiosk.db"), "kiosk.whitelist.api-key=")
                .run(application -> {
                    assertThat(application).hasNotFailed();
                    assertThat(application.getEnvironment().getProperty("kiosk.whitelist.sync-enabled", Boolean.class))
                            .isEqualTo(enabled);
                    if (enabled) {
                        assertThat(application).hasSingleBean(RestWhitelistSnapshotSource.class)
                                .hasSingleBean(SyncWhitelistUseCase.class);
                    } else {
                        assertThat(application).doesNotHaveBean(IWhitelistSnapshotSource.class)
                                .doesNotHaveBean(SyncWhitelistUseCase.class);
                    }
                    assertThat(application.getBean(SQLiteWhitelistRepository.class).inspect()).isEmpty();
                });
    }

    @Test
    void manualHttpTriggerUsesTheSharedGuardAndCommitsThroughTheRealPipeline() {
        seedOld();
        var mvcRef = new AtomicReference<MockMvc>();
        http.expect(requestTo(URL)).andRespond(request -> {
            assertThat(transactions).hasValue(0);
            try {
                mvcRef.get().perform(post("/api/dev/kiosk/whitelist/sync"))
                        .andExpect(status().isConflict())
                        .andExpect(jsonPath("$.code").value("WHITELIST_SYNC_IN_PROGRESS"));
            } catch (Exception failure) {
                throw new java.io.IOException("manual trigger test failed", failure);
            }
            return withSuccess(employees(), MediaType.APPLICATION_JSON).header("X-Whitelist-Version", "5")
                    .createResponse(request);
        });
        context.withUserConfiguration(DevWhitelistSyncController.class)
                .withPropertyValues("spring.profiles.active=dev").run(application -> {
                    assertThat(application).hasSingleBean(SyncWhitelistUseCase.class);
                    var mvc = MockMvcBuilders
                            .standaloneSetup(application.getBean(DevWhitelistSyncController.class)).build();
                    mvcRef.set(mvc);
                    mvc.perform(post("/api/dev/kiosk/whitelist/sync"))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.status").value("UPDATED"))
                            .andExpect(jsonPath("$.snapshotVersion").value(5));
                    assertThat(stored.inspect().orElseThrow().version()).isEqualTo(5);
                    assertThat(stored.lookup("NEW").employee()).isPresent();
                    centralSync.setDisabled(true);
                    mvc.perform(post("/api/dev/kiosk/whitelist/sync"))
                            .andExpect(status().isConflict())
                            .andExpect(jsonPath("$.code").value("CENTRAL_SYNC_DISABLED"));
                    assertThat(stored.inspect().orElseThrow().version()).isEqualTo(5);
                });
    }

    private DataSource observedDatabase() {
        return new DelegatingDataSource(database) {
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
                                        String sql = statementMethod.getName().startsWith("execute")
                                                ? (String) statementArgs[0] : "";
                                        sqlHook.before(sql);
                                        Object value = invoke(statement, statementMethod, statementArgs);
                                        if (sql.equals("BEGIN") || sql.equals("BEGIN IMMEDIATE")) {
                                            transactions.incrementAndGet();
                                            if (sql.equals("BEGIN IMMEDIATE")) {
                                                writeBegins.incrementAndGet();
                                            }
                                        } else if (sql.equals("COMMIT") || sql.equals("ROLLBACK")) {
                                            transactions.decrementAndGet();
                                            if (sql.equals("COMMIT")) {
                                                afterCommit.run();
                                            }
                                        }
                                        return value;
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
        void before(String sql) throws SQLException;
    }
}
