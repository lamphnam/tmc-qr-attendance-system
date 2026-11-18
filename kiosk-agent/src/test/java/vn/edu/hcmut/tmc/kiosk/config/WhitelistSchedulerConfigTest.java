package vn.edu.hcmut.tmc.kiosk.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

import vn.edu.hcmut.tmc.kiosk.application.usecase.CentralSyncSwitch;
import vn.edu.hcmut.tmc.kiosk.application.usecase.SyncWhitelistUseCase;
import vn.edu.hcmut.tmc.kiosk.application.usecase.WhitelistSyncScheduler;
import vn.edu.hcmut.tmc.kiosk.domain.model.KioskIdentity;
import vn.edu.hcmut.tmc.kiosk.domain.model.WhitelistMetadata;
import vn.edu.hcmut.tmc.kiosk.domain.model.WhitelistSnapshot;
import vn.edu.hcmut.tmc.kiosk.domain.port.IWhitelistSnapshotSource;
import vn.edu.hcmut.tmc.kiosk.domain.port.IWhitelistSnapshotStore;

class WhitelistSchedulerConfigTest {

    private final AtomicInteger fetches = new AtomicInteger();
    private final CountDownLatch fetched = new CountDownLatch(1);

    private ApplicationContextRunner context() {
        return new ApplicationContextRunner().withUserConfiguration(WhitelistSyncWiringConfig.class)
                .withBean(IWhitelistSnapshotSource.class, () -> (kiosk, factory) -> {
                    fetches.incrementAndGet();
                    fetched.countDown();
                    return new WhitelistSnapshot(new WhitelistMetadata(kiosk, factory, 1, Instant.EPOCH, 0), List.of());
                })
                .withBean(IWhitelistSnapshotStore.class, () -> snapshot -> true)
                .withBean("centralRestClient", RestClient.class, () -> RestClient.builder().build())
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(KioskIdentity.class, () -> new KioskIdentity("BD-K01", "BD", "BD-G01"))
                .withBean(Clock.class, Clock::systemUTC)
                .withBean(CentralSyncSwitch.class, () -> new CentralSyncSwitch(false));
    }

    @Test
    void missingFlagNeverCreatesAnActiveSchedulerOrUseCase() {
        context().run(application -> {
            assertThat(application).hasNotFailed().doesNotHaveBean(WhitelistSyncScheduler.class)
                    .doesNotHaveBean(SyncWhitelistUseCase.class);
            assertThat(fetches).hasValue(0);
        });
    }

    @ParameterizedTest
    @CsvSource({"dev,true,true", "test,true,false", "default,true,false", "dev,false,false", "test,false,false"})
    void initialPassRequiresDevAndFlagAndDoesNotRunBeforeReady(String profile, boolean enabled, boolean initial) {
        context().withPropertyValues("spring.profiles.active=" + profile, "kiosk.whitelist.sync-enabled=" + enabled,
                "kiosk.whitelist.sync-interval-ms=86400000").run(application -> {
                    assertThat(application).hasNotFailed();
                    assertThat(fetches).hasValue(0);
                    if (initial) {
                        assertThat(application.getBean(WhitelistSyncScheduler.class).isRunning()).isFalse();
                    }
                    var ready = new ApplicationReadyEvent(new SpringApplication(), new String[0],
                            application.getSourceApplicationContext(), Duration.ZERO);
                    application.publishEvent(ready);
                    application.publishEvent(ready);
                    if (initial) {
                        assertThat(fetched.await(5, TimeUnit.SECONDS)).isTrue();
                        assertThat(fetches).hasValue(1);
                        assertThat(application.getBean(WhitelistSyncScheduler.class).isRunning()).isTrue();
                    } else {
                        assertThat(fetches).hasValue(0);
                        assertThat(application).doesNotHaveBean("initialWhitelistSync");
                    }
                });
    }

    @Test
    void devWithoutFlagHasNoInitialListener() {
        context().withPropertyValues("spring.profiles.active=dev").run(application -> {
            assertThat(application).hasNotFailed().doesNotHaveBean("initialWhitelistSync");
            application.publishEvent(new ApplicationReadyEvent(new SpringApplication(), new String[0],
                    application.getSourceApplicationContext(), Duration.ZERO));
            assertThat(fetches).hasValue(0);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "not-an-interval"})
    void disabledFlagDoesNotValidateUnusedIntervalOrCreateAWorker(String interval) {
        context().withPropertyValues("kiosk.whitelist.sync-enabled=false", "kiosk.whitelist.sync-interval-ms=" + interval)
                .run(application -> {
                    assertThat(application).hasNotFailed().doesNotHaveBean(WhitelistSyncScheduler.class);
                    assertThat(fetches).hasValue(0);
                });
    }

    @Test
    void enabledWithMissingIntervalUsesDefaultAndStartsQuietlyWithoutGlobalSchedulerBeans() {
        context().withPropertyValues("kiosk.whitelist.sync-enabled=true").run(application -> {
            assertThat(application).hasNotFailed().hasSingleBean(WhitelistSyncScheduler.class)
                    .hasSingleBean(SyncWhitelistUseCase.class);
            assertThat(application.getBean(WhitelistSyncScheduler.class).isRunning()).isTrue();
            assertThat(fetches).hasValue(0);
            assertThat(application.getBeansOfType(org.springframework.scheduling.TaskScheduler.class)).isEmpty();
            assertThat(application.getBeansOfType(java.util.concurrent.ScheduledExecutorService.class)).isEmpty();
        });
    }

    @Test
    void yamlMapsTheIntervalEnvironmentVariableAndKeepsTheFeatureDisabled() {
        context().withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("KIOSK_WHITELIST_SYNC_INTERVAL_MS=120000", "KIOSK_WHITELIST_SYNC_ENABLED=false")
                .run(application -> {
                    assertThat(application).hasNotFailed().doesNotHaveBean(WhitelistSyncScheduler.class);
                    assertThat(application.getEnvironment().getProperty("kiosk.whitelist.sync-interval-ms", Long.class))
                            .isEqualTo(120_000);
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "999", "86400001", "9223372036854775807", "not-a-number"})
    void invalidIntervalFailsEnabledConfigurationInsteadOfBusyLooping(String interval) {
        context().withPropertyValues("kiosk.whitelist.sync-enabled=true", "kiosk.whitelist.sync-interval-ms=" + interval)
                .run(application -> {
                    assertThat(application).hasFailed();
                    assertThat(fetches).hasValue(0);
                });
    }
}
