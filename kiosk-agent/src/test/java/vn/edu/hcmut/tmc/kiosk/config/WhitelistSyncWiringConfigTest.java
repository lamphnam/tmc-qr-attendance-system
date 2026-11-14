package vn.edu.hcmut.tmc.kiosk.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

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
import vn.edu.hcmut.tmc.kiosk.infrastructure.config.WhitelistHttpConfig;
import vn.edu.hcmut.tmc.kiosk.infrastructure.persistence.SQLiteWhitelistRepository;
import vn.edu.hcmut.tmc.kiosk.infrastructure.persistence.SqliteConfig;
import vn.edu.hcmut.tmc.kiosk.infrastructure.security.WhitelistSecretCodec;
import vn.edu.hcmut.tmc.kiosk.infrastructure.sync.WhitelistEmployeeMapper;
import vn.edu.hcmut.tmc.kiosk.infrastructure.sync.WhitelistHttpTransport;

/** Sources here are explicitly test-only revision-bearing fixtures, never a production HTTP fallback. */
class WhitelistSyncWiringConfigTest {

    private static final KioskIdentity IDENTITY = new KioskIdentity("BD-K01", "BD", "BD-G01");
    private static final Instant NOW = Instant.parse("2026-11-14T04:00:00.123456789Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @TempDir
    Path dir;

    private SQLiteWhitelistRepository store;
    private MockRestServiceServer http;
    private ApplicationContextRunner context;
    private final CentralSyncSwitch centralSync = new CentralSyncSwitch(false);

    @BeforeEach
    void contextWithExistingDependencies() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        WhitelistSecretCodec codec = WhitelistSecretCodec.fromBase64Key(Base64.getEncoder().encodeToString(key));
        Arrays.fill(key, (byte) 0);
        store = new SQLiteWhitelistRepository(SqliteConfig.createDataSource(dir.resolve("kiosk.db")), IDENTITY, codec);
        RestClient.Builder builder = RestClient.builder().baseUrl("http://central.invalid");
        http = MockRestServiceServer.bindTo(builder).build();
        RestClient sharedClient = builder.build();
        context = new ApplicationContextRunner()
                .withUserConfiguration(WhitelistHttpConfig.class, WhitelistSyncWiringConfig.class)
                .withBean("centralRestClient", RestClient.class, () -> sharedClient)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(KioskIdentity.class, () -> IDENTITY)
                .withBean(Clock.class, () -> CLOCK)
                .withBean(CentralSyncSwitch.class, () -> centralSync)
                .withBean(IWhitelistSnapshotStore.class, () -> store)
                .withPropertyValues("kiosk.whitelist.api-key=", "kiosk.whitelist.sync-interval-ms=86400000");
    }

    private static WhitelistSnapshot testSnapshot() {
        WhitelistEmployee employee = new WhitelistEmployee("EMP-001", QrStatus.ACTIVE, 3,
                "synthetic-test-material".getBytes(StandardCharsets.UTF_8));
        return new WhitelistSnapshot(new WhitelistMetadata("BD-K01", "BD", 7, Instant.EPOCH, 1), List.of(employee));
    }

    @Test
    void missingSourceAndHttpKeyLeaveTransportMapperAndStoreAvailableWithoutCreatingASyncBean() {
        context.run(application -> {
            assertThat(application).hasNotFailed().hasSingleBean(WhitelistHttpTransport.class)
                    .hasSingleBean(WhitelistEmployeeMapper.class).hasSingleBean(IWhitelistSnapshotStore.class)
                    .doesNotHaveBean(IWhitelistSnapshotSource.class).doesNotHaveBean(SyncWhitelistUseCase.class);
            assertThat(application.getBean(IWhitelistSnapshotStore.class)).isSameAs(store);
            assertThat(store.inspect()).isEmpty();
            http.verify();
        });
    }

    @Test
    void sourceEnablesOneSharedSingletonAndUsesTheExistingIdentityClockAndStore() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<SyncWhitelistUseCase> otherConsumer = new AtomicReference<>();
        IWhitelistSnapshotSource fixture = (kioskId, factoryId) -> {
            assertThat(kioskId).isEqualTo(IDENTITY.kioskId());
            assertThat(factoryId).isEqualTo(IDENTITY.factoryId());
            calls.incrementAndGet();
            assertThat(otherConsumer.get().run().status()).isEqualTo(Status.ALREADY_RUNNING);
            return testSnapshot();
        };
        context.withPropertyValues("kiosk.whitelist.sync-enabled=true")
                .withBean(IWhitelistSnapshotSource.class, () -> fixture).run(application -> {
            assertThat(application).hasNotFailed().hasSingleBean(SyncWhitelistUseCase.class);
            SyncWhitelistUseCase first = application.getBean(SyncWhitelistUseCase.class);
            otherConsumer.set(application.getBean(SyncWhitelistUseCase.class));
            assertThat(first).isSameAs(otherConsumer.get());
            assertThat(application.getBeanFactory().isSingleton("syncWhitelistUseCase")).isTrue();
            assertThat(store.inspect()).as("bean creation must not run the source").isEmpty();
            assertThat(calls).hasValue(0);

            assertThat(first.run().status()).isEqualTo(Status.UPDATED);

            assertThat(calls).hasValue(1);
            assertThat(store.inspect()).contains(new WhitelistMetadata("BD-K01", "BD", 7,
                    NOW.truncatedTo(ChronoUnit.MILLIS), 1));
            assertThat(store.lookup("EMP-001").employee()).contains(testSnapshot().employees().getFirst());
            http.verify();
        });
    }

    @Test
    void sharedOfflineSwitchPreventsSourceNetworkAndPersistenceUntilExplicitlyReenabled() {
        centralSync.setDisabled(true);
        AtomicInteger calls = new AtomicInteger();
        IWhitelistSnapshotSource fixture = (kioskId, factoryId) -> {
            calls.incrementAndGet();
            return testSnapshot();
        };
        context.withPropertyValues("kiosk.whitelist.sync-enabled=true")
                .withBean(IWhitelistSnapshotSource.class, () -> fixture).run(application -> {
            SyncWhitelistUseCase useCase = application.getBean(SyncWhitelistUseCase.class);
            assertThat(application.getBean(CentralSyncSwitch.class)).isSameAs(centralSync);
            assertThat(useCase.run().status()).isEqualTo(Status.OFFLINE);
            assertThat(calls).hasValue(0);
            assertThat(store.inspect()).isEmpty();
            http.verify();

            application.getBean(CentralSyncSwitch.class).setDisabled(false);
            assertThat(useCase.run().status()).isEqualTo(Status.UPDATED);
            assertThat(calls).hasValue(1);
        });
    }

    @Test
    void explicitlyProvidedUseCaseIsNotDuplicatedByConditionalWiring() {
        IWhitelistSnapshotSource fixture = (kioskId, factoryId) -> testSnapshot();
        SyncWhitelistUseCase provided = new SyncWhitelistUseCase(fixture, store, centralSync, IDENTITY, CLOCK);
        context.withPropertyValues("kiosk.whitelist.sync-enabled=true")
                .withBean(IWhitelistSnapshotSource.class, () -> fixture)
                .withBean(SyncWhitelistUseCase.class, () -> provided).run(application -> {
                    assertThat(application).hasNotFailed().hasSingleBean(SyncWhitelistUseCase.class);
                    assertThat(application.getBean(SyncWhitelistUseCase.class)).isSameAs(provided);
                    assertThat(store.inspect()).isEmpty();
                    http.verify();
                });
    }
}
