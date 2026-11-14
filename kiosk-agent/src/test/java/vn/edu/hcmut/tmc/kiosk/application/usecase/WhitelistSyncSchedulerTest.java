package vn.edu.hcmut.tmc.kiosk.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import vn.edu.hcmut.tmc.kiosk.domain.exception.CentralUnavailableException;
import vn.edu.hcmut.tmc.kiosk.domain.model.KioskIdentity;
import vn.edu.hcmut.tmc.kiosk.domain.model.WhitelistMetadata;
import vn.edu.hcmut.tmc.kiosk.domain.model.WhitelistSnapshot;
import vn.edu.hcmut.tmc.kiosk.domain.port.IWhitelistSnapshotSource;

@ExtendWith(OutputCaptureExtension.class)
class WhitelistSyncSchedulerTest {

    private static final String SENSITIVE = "synthetic-upstream-key-secret-body-ciphertext";
    private ScheduledExecutorService executor;
    private ScheduledFuture<?> future;
    private final AtomicReference<Runnable> tick = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();
    private final CentralSyncSwitch centralSync = new CentralSyncSwitch(false);

    @BeforeEach
    void controlledExecutor() throws InterruptedException {
        executor = mock(ScheduledExecutorService.class);
        future = mock(ScheduledFuture.class);
        when(executor.scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS)))
                .thenAnswer(invocation -> {
                    tick.set(invocation.getArgument(0));
                    return future;
                });
        when(executor.awaitTermination(10, TimeUnit.SECONDS)).thenReturn(true);
    }

    private static WhitelistSnapshot snapshot() {
        return new WhitelistSnapshot(new WhitelistMetadata("BD-K01", "BD", 1, Instant.EPOCH, 0), List.of());
    }

    private SyncWhitelistUseCase useCase(IWhitelistSnapshotSource source) {
        return new SyncWhitelistUseCase(source, snapshot -> true, centralSync,
                new KioskIdentity("BD-K01", "BD", "BD-G01"), Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
    }

    private WhitelistSyncScheduler scheduler(IWhitelistSnapshotSource source) {
        return new WhitelistSyncScheduler(useCase(source), 60_000, () -> executor);
    }

    @Test
    void registersFixedDelayAndInitialDelayWithoutExecutingAtStartAndStopsCleanly() {
        var scheduler = scheduler((kiosk, factory) -> {
            calls.incrementAndGet();
            return snapshot();
        });
        assertThat(scheduler.isRunning()).isFalse();
        assertThat(tick.get()).isNull();
        scheduler.start();
        scheduler.start();
        assertThat(scheduler.isRunning()).isTrue();
        assertThat(calls).hasValue(0);
        verify(executor, times(1)).scheduleWithFixedDelay(any(Runnable.class), eq(60_000L), eq(60_000L),
                eq(TimeUnit.MILLISECONDS));
        tick.get().run();
        tick.get().run();
        assertThat(calls).hasValue(2);
        scheduler.stop();
        scheduler.stop();
        assertThat(scheduler.isRunning()).isFalse();
        verify(future).cancel(false);
        verify(executor).shutdown();
        tick.get().run();
        assertThat(calls).hasValue(2);
    }

    @Test
    void offlineSkipsFetchingAndResumesOnTheNextTick() {
        var scheduler = scheduler((kiosk, factory) -> {
            calls.incrementAndGet();
            return snapshot();
        });
        scheduler.start();
        try {
            centralSync.setDisabled(true);
            tick.get().run();
            assertThat(calls).hasValue(0);
            centralSync.setDisabled(false);
            tick.get().run();
            assertThat(calls).hasValue(1);
        } finally {
            scheduler.stop();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"source-unavailable", "unexpected"})
    void failuresNeverLeakDiagnosticsOrCancelLaterPasses(String kind, CapturedOutput output) {
        var scheduler = scheduler((kiosk, factory) -> {
            if (calls.getAndIncrement() == 0) {
                if (kind.equals("source-unavailable")) {
                    throw new CentralUnavailableException(SENSITIVE);
                }
                throw new IllegalStateException(SENSITIVE);
            }
            return snapshot();
        });
        scheduler.start();
        try {
            tick.get().run();
            tick.get().run();
            assertThat(calls).hasValue(2);
            assertThat(scheduler.isRunning()).isTrue();
            assertThat(output).doesNotContain(SENSITIVE);
            assertThat(output).contains("retrying next interval", "whitelist sync committed");
        } finally {
            scheduler.stop();
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 0, 1, 999, 86_400_001, Long.MAX_VALUE})
    void rejectsUnsafeIntervalsBeforeCreatingAnExecutor(long interval) {
        assertThatThrownBy(() -> new WhitelistSyncScheduler(useCase((kiosk, factory) -> snapshot()), interval,
                () -> { throw new AssertionError("executor must not be created"); }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("kiosk.whitelist.sync-interval-ms must be between 1000 and 86400000");
    }

    @Test
    void lifecycleRestartRequestsAnExecutorAgainAfterShutdown() {
        AtomicInteger created = new AtomicInteger();
        var scheduler = new WhitelistSyncScheduler(useCase((kiosk, factory) -> snapshot()), 1_000,
                () -> { created.incrementAndGet(); return executor; });
        scheduler.start();
        scheduler.stop();
        scheduler.start();
        scheduler.stop();
        assertThat(created).hasValue(2);
        verify(executor, times(2)).scheduleWithFixedDelay(any(Runnable.class), eq(1_000L), eq(1_000L), eq(TimeUnit.MILLISECONDS));
    }

    @Test
    void realExecutorWaitsTheFullFirstCycleAndUsesItsOwnThread() throws Exception {
        CountDownLatch fetched = new CountDownLatch(1);
        AtomicReference<String> thread = new AtomicReference<>();
        var scheduler = new WhitelistSyncScheduler(useCase((kiosk, factory) -> {
            thread.set(Thread.currentThread().getName());
            fetched.countDown();
            return snapshot();
        }), 1_000);
        long start = System.nanoTime();
        scheduler.start();
        try {
            assertThat(fetched.await(200, TimeUnit.MILLISECONDS)).isFalse();
            assertThat(fetched.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(System.nanoTime() - start).isGreaterThanOrEqualTo(TimeUnit.SECONDS.toNanos(1));
            assertThat(thread.get()).isEqualTo("tmc-whitelist-sync");
        } finally {
            scheduler.stop();
        }
    }

    @Test
    void longScheduledFetchDoesNotOverlapAndExplicitTriggersShareTheExistingGuard() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch secondFetch = new CountDownLatch(1);
        SyncWhitelistUseCase useCase = useCase((kiosk, factory) -> {
            if (calls.incrementAndGet() == 1) {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test coordination timed out");
                    }
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("test coordination interrupted");
                }
            } else {
                secondFetch.countDown();
            }
            return snapshot();
        });
        var scheduler = new WhitelistSyncScheduler(useCase, 1_000);
        scheduler.start();
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(useCase.run().status()).isEqualTo(WhitelistSyncReport.Status.ALREADY_RUNNING);
            assertThat(secondFetch.await(1_200, TimeUnit.MILLISECONDS)).isFalse();
            assertThat(calls).hasValue(1);
            release.countDown();
            assertThat(secondFetch.await(200, TimeUnit.MILLISECONDS)).isFalse();
            assertThat(secondFetch.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(calls.get()).isGreaterThanOrEqualTo(2);
        } finally {
            release.countDown();
            scheduler.stop();
        }
    }
}
