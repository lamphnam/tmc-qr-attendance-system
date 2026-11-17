package vn.edu.hcmut.tmc.kiosk.application.usecase;

import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/** Owns a private fixed-delay executor so whitelist HTTP cannot occupy Attendance's Spring scheduler thread. */
public final class WhitelistSyncScheduler implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(WhitelistSyncScheduler.class);
    private final SyncWhitelistUseCase useCase;
    private final long intervalMs;
    private final Supplier<ScheduledExecutorService> executorFactory;
    private final boolean waitUntilReady;
    private ScheduledExecutorService executor;
    private ScheduledFuture<?> task;
    private volatile boolean running;
    private boolean initialSyncRequested;

    public WhitelistSyncScheduler(SyncWhitelistUseCase useCase, long intervalMs) {
        this(useCase, intervalMs, false);
    }

    public WhitelistSyncScheduler(SyncWhitelistUseCase useCase, long intervalMs, boolean waitUntilReady) {
        this(useCase, intervalMs, () -> Executors.newSingleThreadScheduledExecutor(action -> {
            Thread thread = new Thread(action, "tmc-whitelist-sync");
            thread.setDaemon(true);
            return thread;
        }), waitUntilReady);
    }

    // Control time/executor lifecycle in tests without adding a new scheduling abstraction.
    WhitelistSyncScheduler(SyncWhitelistUseCase useCase, long intervalMs,
            Supplier<ScheduledExecutorService> executorFactory) {
        this(useCase, intervalMs, executorFactory, false);
    }

    private WhitelistSyncScheduler(SyncWhitelistUseCase useCase, long intervalMs,
            Supplier<ScheduledExecutorService> executorFactory, boolean waitUntilReady) {
        this.useCase = Objects.requireNonNull(useCase, "useCase");
        if (intervalMs < 1_000 || intervalMs > 86_400_000) {
            throw new IllegalArgumentException("kiosk.whitelist.sync-interval-ms must be between 1000 and 86400000");
        }
        this.intervalMs = intervalMs;
        this.executorFactory = Objects.requireNonNull(executorFactory, "executorFactory");
        this.waitUntilReady = waitUntilReady;
    }

    @Override
    public boolean isAutoStartup() {
        return !waitUntilReady;
    }

    /** The lifecycle monitor protects registration/shutdown only, never HTTP, storage or the use-case guard. */
    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        executor = executorFactory.get();
        try {
            running = true;
            task = executor.scheduleWithFixedDelay(this::tick, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        } catch (RuntimeException failure) {
            running = false;
            executor.shutdown();
            throw failure;
        }
    }

    /** Queue one ready-time pass on the same worker; network work never runs on the Spring event thread. */
    public synchronized void requestInitialSync() {
        if (!running || initialSyncRequested) {
            return;
        }
        executor.execute(this::tick);
        initialSyncRequested = true;
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        task.cancel(false);
        executor.shutdown();
        try {
            // Let an in-flight bounded HTTP/SQLite pass finish; do not interrupt a commit.
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                log.warn("whitelist sync is still finishing during shutdown");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            log.warn("whitelist scheduler shutdown wait was interrupted");
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void tick() {
        if (!running) {
            return;
        }
        try {
            WhitelistSyncReport report = useCase.run();
            switch (report.status()) {
                case UPDATED -> log.info("whitelist sync committed, sourceVersion={}", report.snapshotVersion().orElseThrow());
                case NOT_NEWER, OFFLINE, ALREADY_RUNNING -> log.debug("whitelist sync skipped, status={}", report.status());
                case SOURCE_UNAVAILABLE, INVALID_SNAPSHOT, CACHE_UNAVAILABLE, INVALID_CACHE ->
                    log.warn("whitelist sync kept cache, status={}; retrying next interval", report.status());
            }
        } catch (RuntimeException failure) {
            // Never retain an untrusted exception in logs, and never let it cancel future periodic executions.
            log.warn("whitelist sync pass failed; retrying next interval");
        }
    }
}
