package vn.edu.hcmut.tmc.kiosk.application.usecase;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Background trigger for {@link SyncPendingEventsUseCase}; this is outside the scan fast path (docs/ARCHITECTURE.md). */
@Component
@ConditionalOnProperty(name = "kiosk.sync.enabled", havingValue = "true", matchIfMissing = true)
public class SyncManagerScheduler {

    private static final Logger log = LoggerFactory.getLogger(SyncManagerScheduler.class);

    private final SyncPendingEventsUseCase syncPendingEvents;

    public SyncManagerScheduler(SyncPendingEventsUseCase syncPendingEvents) {
        this.syncPendingEvents = syncPendingEvents;
    }

    @Scheduled(initialDelayString = "${kiosk.sync.interval-ms}", fixedDelayString = "${kiosk.sync.interval-ms}")
    void tick() {
        try {
            SyncRunReport report = syncPendingEvents.run();
            if (report.attempted() > 0) {
                log.info("sync pass: attempted={} synced={} failed={} retryScheduled={}",
                        report.attempted(), report.synced(), report.failed(), report.retryScheduled());
            }
        } catch (RuntimeException e) {
            // The scheduler must survive anything (e.g. a transient SQLite lock) or the queue would never drain.
            log.error("sync pass crashed, will retry on the next tick: {}", e.toString());
        }
    }
}
