package vn.edu.hcmut.tmc.reporting.application.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import vn.edu.hcmut.tmc.reporting.domain.model.BatchStatus;
import vn.edu.hcmut.tmc.reporting.domain.model.DataScope;
import vn.edu.hcmut.tmc.reporting.domain.model.DeliveryResult;
import vn.edu.hcmut.tmc.reporting.domain.model.ExportAlert;
import vn.edu.hcmut.tmc.reporting.domain.model.ExportPeriod;
import vn.edu.hcmut.tmc.reporting.domain.model.HrExportBatch;
import vn.edu.hcmut.tmc.reporting.domain.model.PayrollBatch;
import vn.edu.hcmut.tmc.reporting.domain.model.PayrollSelection;
import vn.edu.hcmut.tmc.reporting.domain.model.TriggerSource;
import vn.edu.hcmut.tmc.reporting.domain.port.IExportAlertNotifier;
import vn.edu.hcmut.tmc.reporting.domain.port.IHrExportBatchStore;
import vn.edu.hcmut.tmc.reporting.domain.port.IHrPayrollGateway;
import vn.edu.hcmut.tmc.reporting.domain.service.ExportRetryPolicy;
import vn.edu.hcmut.tmc.reporting.domain.service.ExportSchedule;

/**
 * Use case: export a month of accepted attendance to HR/Payroll exactly once per period (ADR-03, NFR-3, NFR-6).
 *
 * <p>It decides <em>what</em> happens to a batch. When to run is {@link ExportSchedule}, how long to wait is
 * {@link ExportRetryPolicy}, how the batch travels is whichever {@link IHrPayrollGateway} is wired, and SQL is the
 * {@link IHrExportBatchStore}. None of them is visible here beyond its port.
 *
 * <p>No database transaction is held across the network call: an attempt first claims the batch with a compare-and-set
 * (status SENDING plus a lease), sends, then records the result with a second compare-and-set. A crash in between leaves
 * the batch SENDING until the lease expires, and the next poll resends it; the receiver dedupes by batch and attendance
 * id, so a resend is safe.
 */
@Service
public class HrExportService {

    private static final Logger log = LoggerFactory.getLogger(HrExportService.class);

    private final IHrExportBatchStore store;
    private final IHrPayrollGateway gateway;
    private final IExportAlertNotifier alerts;
    private final ExportSchedule schedule;
    private final ExportRetryPolicy retryPolicy;
    private final DataScope scope;
    private final HrExportSettings settings;
    private final Clock clock;

    public HrExportService(IHrExportBatchStore store, IHrPayrollGateway gateway, IExportAlertNotifier alerts,
                           ExportSchedule schedule, ExportRetryPolicy retryPolicy, DataScope scope,
                           HrExportSettings settings, Clock clock) {
        this.store = store;
        this.gateway = gateway;
        this.alerts = alerts;
        this.schedule = schedule;
        this.retryPolicy = retryPolicy;
        this.scope = scope;
        this.settings = settings;
        this.clock = clock;
    }

    /**
     * The background poll: makes sure the month that is due has its batch (this also catches up a run missed while the
     * service was down), then performs every due attempt.
     */
    public List<HrExportResult> runDueWork() {
        Instant now = clock.instant();
        ExportPeriod due = schedule.periodDueAt(now);
        store.createIfAbsent(HrExportBatch.newBatch(due, TriggerSource.SCHEDULED, now, retryPolicy.deadlineFor(now)),
                new PayrollSelection(due, scope));

        List<HrExportResult> results = new ArrayList<>();
        for (HrExportBatch batch : store.findDue(now, settings.dueBatchLimit())) {
            results.add(attempt(batch));
        }
        return results;
    }

    /**
     * HR/Admin asks for a month now (a demo bypass of the schedule, not another data contract). Reuses the period's
     * batch if one exists: a delivered month is never sent twice, a FAILED one is re-armed, a PENDING one is attempted
     * without waiting for its backoff.
     *
     * @throws IllegalArgumentException if the month is not over yet in factory time
     */
    public HrExportResult triggerManually(ExportPeriod period) {
        Instant now = clock.instant();
        if (!period.isCompleteAt(now)) {
            throw new IllegalArgumentException("period " + period + " is not over yet in factory time");
        }
        HrExportBatch batch = store.createIfAbsent(
                HrExportBatch.newBatch(period, TriggerSource.MANUAL, now, retryPolicy.deadlineFor(now)),
                new PayrollSelection(period, scope));

        return switch (batch.status()) {
            case SENT, ACKNOWLEDGED -> HrExportResult.alreadyExported(batch);
            case SENDING -> batch.isLeaseHeldAt(now) ? HrExportResult.inProgress(batch) : attempt(batch);
            case PENDING -> attempt(batch);
            case FAILED -> rearmAndAttempt(batch, now);
        };
    }

    public Optional<HrExportBatch> find(ExportPeriod period) {
        return store.find(period);
    }

    private HrExportResult rearmAndAttempt(HrExportBatch failed, Instant now) {
        HrExportBatch rearmed = failed.rearm(now, retryPolicy.deadlineFor(now), TriggerSource.MANUAL);
        if (!store.compareAndSet(failed, rearmed)) {
            return HrExportResult.inProgress(failed);
        }
        return attempt(rearmed);
    }

    private HrExportResult attempt(HrExportBatch batch) {
        HrExportBatch claimed = batch.startAttempt(clock.instant(), settings.attemptLease());
        if (!store.compareAndSet(batch, claimed)) {
            return HrExportResult.inProgress(batch);
        }

        DeliveryResult delivery = send(claimed);
        Instant finishedAt = clock.instant();
        HrExportBatch next = switch (delivery.outcome()) {
            case ACKNOWLEDGED -> claimed.acknowledged();
            case DELIVERED -> claimed.sent();
            case RETRYABLE -> retryPolicy.nextAttemptAfter(claimed.attemptCount(), finishedAt, claimed.retryDeadline())
                    .map(at -> claimed.retryAt(at, delivery.detail()))
                    .orElseGet(() -> claimed.failed("retry deadline passed; last error: " + delivery.detail()));
            case REJECTED -> claimed.failed("rejected by the receiver: " + delivery.detail());
        };

        if (!store.compareAndSet(claimed, next)) {
            log.warn("hr-export period={} attempt={} finished after its lease expired; the newer attempt's state is kept",
                    claimed.period(), claimed.attemptCount());
            return HrExportResult.inProgress(claimed);
        }
        log.info("hr-export period={} batch={} attempt={} items={} outcome={} status={}", next.period(), next.batchUuid(),
                next.attemptCount(), next.itemCount(), delivery.outcome(), next.status());
        if (next.status() == BatchStatus.FAILED) {
            raiseAlert(next, finishedAt);
        }
        return HrExportResult.afterAttempt(next, delivery);
    }

    private DeliveryResult send(HrExportBatch claimed) {
        PayrollBatch payload = new PayrollBatch(claimed.batchUuid(), claimed.period(), store.loadRecords(claimed.batchUuid()));
        try {
            return gateway.send(payload);
        } catch (RuntimeException brokenContract) {
            // IHrPayrollGateway promises not to throw; if one does, keep the batch retryable instead of losing the poll.
            log.error("hr-export channel {} threw {} although its contract forbids it",
                    gateway.getClass().getSimpleName(), brokenContract.getClass().getSimpleName());
            return DeliveryResult.retryable("channel error (" + brokenContract.getClass().getSimpleName() + ")");
        }
    }

    private void raiseAlert(HrExportBatch failed, Instant now) {
        try {
            alerts.exportFailed(new ExportAlert(failed.batchUuid(), failed.period(), failed.attemptCount(),
                    failed.lastError(), now));
        } catch (RuntimeException e) {
            log.error("hr-export alert for period={} could not be raised: {}", failed.period(), e.getClass().getSimpleName());
        }
    }
}
