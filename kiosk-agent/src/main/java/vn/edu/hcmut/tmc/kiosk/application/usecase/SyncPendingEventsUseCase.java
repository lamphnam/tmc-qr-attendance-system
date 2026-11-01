package vn.edu.hcmut.tmc.kiosk.application.usecase;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import vn.edu.hcmut.tmc.kiosk.domain.exception.CentralUnavailableException;
import vn.edu.hcmut.tmc.kiosk.domain.model.EventSyncResult;
import vn.edu.hcmut.tmc.kiosk.domain.model.KioskIdentity;
import vn.edu.hcmut.tmc.kiosk.domain.model.LocalAttendanceEvent;
import vn.edu.hcmut.tmc.kiosk.domain.port.IAttendanceLocalRepository;
import vn.edu.hcmut.tmc.kiosk.domain.port.IKioskSyncAPI;
import vn.edu.hcmut.tmc.kiosk.domain.service.RetryBackoffPolicy;

/**
 * Store-and-forward pass (ADR-01). A row becomes SYNCED only when central names its id in the response, which
 * central does only after its own commit. Everything else stays PENDING with a backoff, or goes FAILED when central
 * rejects it permanently.
 *
 * <p>If a whole request fails (timeout, 5xx, refused) nothing is assumed committed and the same ids are retried;
 * central's unique key makes the retry harmless even if the first attempt did commit.
 */
@Service
public class SyncPendingEventsUseCase {

    private static final Logger log = LoggerFactory.getLogger(SyncPendingEventsUseCase.class);

    private final IAttendanceLocalRepository repository;
    private final IKioskSyncAPI syncApi;
    private final RetryBackoffPolicy backoff;
    private final CentralSyncSwitch centralSync;
    private final KioskIdentity identity;
    private final SyncSettings settings;
    private final Clock clock;
    private final ReentrantLock running = new ReentrantLock();

    public SyncPendingEventsUseCase(IAttendanceLocalRepository repository, IKioskSyncAPI syncApi,
                                    RetryBackoffPolicy backoff, CentralSyncSwitch centralSync,
                                    KioskIdentity identity, SyncSettings settings, Clock clock) {
        this.repository = repository;
        this.syncApi = syncApi;
        this.backoff = backoff;
        this.centralSync = centralSync;
        this.identity = identity;
        this.settings = settings;
        this.clock = clock;
    }

    public SyncRunReport run() {
        if (centralSync.isDisabled()) {
            return SyncRunReport.skipped("CENTRAL_SYNC_DISABLED");
        }
        if (!running.tryLock()) {
            return SyncRunReport.skipped("SYNC_ALREADY_RUNNING");
        }
        try {
            return syncDueEvents();
        } finally {
            running.unlock();
        }
    }

    private SyncRunReport syncDueEvents() {
        Instant now = clock.instant();
        List<LocalAttendanceEvent> due = repository.findDueForSync(now, settings.batchSize());
        if (due.isEmpty()) {
            return SyncRunReport.idle();
        }

        List<EventSyncResult> results;
        try {
            results = syncApi.syncBatch(identity.kioskId(), due);
        } catch (CentralUnavailableException e) {
            log.warn("central sync failed, {} event(s) stay PENDING: {}", due.size(), e.getMessage());
            due.forEach(event -> scheduleRetry(event, now, e.getMessage()));
            return new SyncRunReport(due.size(), 0, 0, due.size(), "CENTRAL_UNAVAILABLE");
        }

        Map<UUID, EventSyncResult> acknowledged = new HashMap<>();
        results.forEach(result -> acknowledged.putIfAbsent(result.attendanceId(), result));

        int synced = 0;
        int failed = 0;
        int retried = 0;
        for (LocalAttendanceEvent event : due) {
            EventSyncResult result = acknowledged.get(event.attendanceId());
            if (result == null) {
                scheduleRetry(event, now, "central did not acknowledge this id");
                retried++;
                continue;
            }
            switch (result.outcome()) {
                case STORED, ALREADY_EXISTS -> {
                    if (repository.markSynced(event.attendanceId(), now)) {
                        synced++;
                    }
                }
                case REJECTED_PERMANENT -> {
                    if (repository.markFailed(event.attendanceId(), orDefault(result.reason(), "rejected by central"))) {
                        failed++;
                    }
                }
                case RETRYABLE -> {
                    scheduleRetry(event, now, orDefault(result.reason(), "central asked to retry"));
                    retried++;
                }
            }
        }
        return new SyncRunReport(due.size(), synced, failed, retried, null);
    }

    private void scheduleRetry(LocalAttendanceEvent event, Instant now, String reason) {
        repository.scheduleRetry(event.attendanceId(), now.plus(backoff.delayFor(event.retryCount())), reason);
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
