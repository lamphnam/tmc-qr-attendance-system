package vn.edu.hcmut.tmc.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import vn.edu.hcmut.tmc.reporting.application.service.HrExportResult;
import vn.edu.hcmut.tmc.reporting.application.service.HrExportResult.Outcome;
import vn.edu.hcmut.tmc.reporting.application.service.HrExportService;
import vn.edu.hcmut.tmc.reporting.application.service.HrExportSettings;
import vn.edu.hcmut.tmc.reporting.domain.model.BatchStatus;
import vn.edu.hcmut.tmc.reporting.domain.model.DataScope;
import vn.edu.hcmut.tmc.reporting.domain.model.DeliveryResult;
import vn.edu.hcmut.tmc.reporting.domain.model.ExportAlert;
import vn.edu.hcmut.tmc.reporting.domain.model.ExportPeriod;
import vn.edu.hcmut.tmc.reporting.domain.model.HrExportBatch;
import vn.edu.hcmut.tmc.reporting.domain.model.PayrollBatch;
import vn.edu.hcmut.tmc.reporting.domain.model.PayrollRecord;
import vn.edu.hcmut.tmc.reporting.domain.model.TriggerSource;
import vn.edu.hcmut.tmc.reporting.domain.port.IHrPayrollGateway;
import vn.edu.hcmut.tmc.reporting.domain.service.ExportRetryPolicy;
import vn.edu.hcmut.tmc.reporting.domain.service.ExportSchedule;

/**
 * The export use case with a fake clock, a scripted channel (a lambda: the use case knows only the port) and a faithful
 * in-memory store. NFR-6 timing (01:00 run, backoff, give up at 06:00, alert) is checked without waiting.
 */
class HrExportServiceTest {

    /** 1 November 2026, 01:00 in Vietnam. */
    private static final Instant RUN_AT = Instant.parse("2026-10-31T18:00:00Z");
    private static final ExportPeriod OCTOBER = ExportPeriod.parse("2026-10");
    private static final Duration LEASE = Duration.ofMinutes(2);

    private final MutableClock clock = new MutableClock(RUN_AT);
    private final InMemoryHrExportBatchStore store = new InMemoryHrExportBatchStore();
    private final Deque<DeliveryResult> script = new ArrayDeque<>();
    private final List<PayrollBatch> sent = new ArrayList<>();
    private final List<Instant> sentAt = new ArrayList<>();
    private final List<ExportAlert> alerts = new ArrayList<>();
    private DeliveryResult whenScriptEmpty = DeliveryResult.acknowledged(false);

    private final IHrPayrollGateway channel = batch -> {
        sent.add(batch);
        sentAt.add(clock.instant());
        return script.isEmpty() ? whenScriptEmpty : script.removeFirst();
    };

    private HrExportService service;

    @BeforeEach
    void setUp() {
        store.eligible.put(OCTOBER, List.of(record(), record(), record()));
        service = new HrExportService(store, channel, alerts::add,
                new ExportSchedule(LocalTime.of(1, 0)),
                new ExportRetryPolicy(Duration.ofMinutes(1), Duration.ofMinutes(30), Duration.ofHours(5)),
                DataScope.productionOnly(), new HrExportSettings(LEASE, 10), clock);
    }

    @Test
    void at0100OnTheFirstThePreviousMonthIsSentOnceAndNeverAgain() {
        List<HrExportResult> first = service.runDueWork();

        assertThat(first).singleElement().extracting(HrExportResult::outcome).isEqualTo(Outcome.DELIVERED);
        assertThat(sent).singleElement().satisfies(batch -> {
            assertThat(batch.period()).isEqualTo(OCTOBER);
            assertThat(batch.batchUuid()).isEqualTo(OCTOBER.batchUuid());
            assertThat(batch.records()).hasSize(3);
        });
        HrExportBatch stored = store.find(OCTOBER).orElseThrow();
        assertThat(stored.status()).isEqualTo(BatchStatus.ACKNOWLEDGED);
        assertThat(stored.triggerSource()).isEqualTo(TriggerSource.SCHEDULED);

        clock.advance(Duration.ofHours(3));
        assertThat(service.runDueWork()).isEmpty();
        assertThat(sent).hasSize(1);
    }

    @Test
    void at0059OctoberIsNotYetCreated() {
        clock.set(RUN_AT.minusSeconds(60));

        service.runDueWork();

        assertThat(store.find(OCTOBER)).isEmpty();
        assertThat(sent).noneMatch(b -> b.period().equals(OCTOBER));
    }

    @Test
    void aRunMissedWhileTheServiceWasDownIsCaughtUpByTheNextPoll() {
        clock.set(RUN_AT.plus(Duration.ofHours(2)));

        service.runDueWork();

        assertThat(sent).singleElement().extracting(PayrollBatch::period).isEqualTo(OCTOBER);
    }

    @Test
    void restartingAfterTheScheduledDeadlineFailsAndAlertsWithoutSending() {
        clock.set(RUN_AT.plus(Duration.ofHours(7)));

        HrExportResult result = service.runDueWork().get(0);

        assertThat(result.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(result.batch().retryDeadline()).isEqualTo(RUN_AT.plus(Duration.ofHours(5)));
        assertThat(result.batch().attemptCount()).isZero();
        assertThat(sent).isEmpty();
        assertThat(alerts).hasSize(1);
        assertThat(service.triggerManually(OCTOBER).outcome()).isEqualTo(Outcome.DELIVERED);
        assertThat(sent).hasSize(1);
    }

    @Test
    void aQueuedRetryOverdueAfterRestartDoesNotSend() {
        script.add(DeliveryResult.retryable("HTTP 503"));
        service.runDueWork();
        clock.set(RUN_AT.plus(Duration.ofHours(7)));

        assertThat(service.runDueWork()).singleElement().extracting(HrExportResult::outcome)
                .isEqualTo(Outcome.FAILED);
        assertThat(sent).hasSize(1);
        assertThat(alerts).hasSize(1);
    }

    @Test
    void aTransientFailureIsRetriedWithBackoffUsingTheSameBatch() {
        script.add(DeliveryResult.retryable("HTTP 503"));

        HrExportResult first = service.runDueWork().get(0);

        assertThat(first.outcome()).isEqualTo(Outcome.RETRY_SCHEDULED);
        assertThat(first.batch().nextRetryAt()).isEqualTo(RUN_AT.plus(Duration.ofMinutes(1)));
        assertThat(first.batch().lastError()).isEqualTo("HTTP 503");

        clock.advance(Duration.ofSeconds(30));
        assertThat(service.runDueWork()).isEmpty(); // backoff not over

        clock.advance(Duration.ofSeconds(30));
        HrExportResult second = service.runDueWork().get(0);

        assertThat(second.outcome()).isEqualTo(Outcome.DELIVERED);
        assertThat(second.batch().attemptCount()).isEqualTo(2);
        assertThat(sent).extracting(PayrollBatch::batchUuid).containsExactly(OCTOBER.batchUuid(), OCTOBER.batchUuid());
        assertThat(sent.get(1).records()).isEqualTo(sent.get(0).records()); // frozen items, not re-queried
        assertThat(alerts).isEmpty();
    }

    @Test
    void whenHrStaysDownNoAttemptHappensAfter0600ThenTheBatchFailsAndOneAlertIsRaised() {
        whenScriptEmpty = DeliveryResult.retryable("HTTP 503");
        Instant deadline = RUN_AT.plus(Duration.ofHours(5)); // 06:00 VN

        service.runDueWork();
        for (int guard = 0; guard < 100 && store.find(OCTOBER).orElseThrow().status() == BatchStatus.PENDING; guard++) {
            clock.set(store.find(OCTOBER).orElseThrow().nextRetryAt());
            service.runDueWork();
        }

        HrExportBatch failed = store.find(OCTOBER).orElseThrow();
        assertThat(failed.status()).isEqualTo(BatchStatus.FAILED);
        assertThat(failed.lastError()).contains("deadline").contains("HTTP 503");
        assertThat(sentAt).allSatisfy(at -> assertThat(at).isBeforeOrEqualTo(deadline));
        assertThat(sentAt.size()).isBetween(10, 20); // 1,2,4,8,16 then every 30 min for 5 h
        assertThat(alerts).singleElement().satisfies(alert -> {
            assertThat(alert.period()).isEqualTo(OCTOBER);
            assertThat(alert.attempts()).isEqualTo(failed.attemptCount());
        });

        clock.advance(Duration.ofDays(1));
        int attempts = sent.size();
        service.runDueWork();
        assertThat(sent).hasSize(attempts); // FAILED is never retried by the poll
    }

    @Test
    void aRejectedBatchFailsAtOnceWithAnAlert() {
        script.add(DeliveryResult.rejected("HTTP 400"));

        HrExportResult result = service.runDueWork().get(0);

        assertThat(result.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(result.batch().lastError()).contains("HTTP 400");
        assertThat(alerts).hasSize(1);
    }

    @Test
    void aChannelWithoutConfirmationEndsSentAndIsNotRetried() {
        script.add(DeliveryResult.delivered(false));

        HrExportResult result = service.runDueWork().get(0);

        assertThat(result.batch().status()).isEqualTo(BatchStatus.SENT);
        clock.advance(Duration.ofHours(1));
        service.runDueWork();
        assertThat(sent).hasSize(1);
    }

    @Test
    void aManualTriggerForADeliveredMonthReusesTheScheduledBatchAndSendsNothing() {
        service.runDueWork();

        HrExportResult manual = service.triggerManually(OCTOBER);

        assertThat(manual.outcome()).isEqualTo(Outcome.ALREADY_EXPORTED);
        assertThat(manual.batch().batchUuid()).isEqualTo(OCTOBER.batchUuid());
        assertThat(sent).hasSize(1);
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void aManualTriggerFirstCreatesTheBatchAndTheScheduleThenFindsItDone() {
        clock.set(RUN_AT.minusSeconds(3600)); // 1 Nov 00:00 VN: October is over but not yet due
        HrExportResult manual = service.triggerManually(OCTOBER);
        assertThat(manual.outcome()).isEqualTo(Outcome.DELIVERED);
        assertThat(manual.batch().triggerSource()).isEqualTo(TriggerSource.MANUAL);

        clock.set(RUN_AT);
        service.runDueWork();

        assertThat(sent).filteredOn(b -> b.period().equals(OCTOBER)).hasSize(1);
    }

    @Test
    void aManualTriggerSkipsTheBackoffOfAPendingBatch() {
        script.add(DeliveryResult.retryable("HTTP 503"));
        service.runDueWork();

        HrExportResult manual = service.triggerManually(OCTOBER);

        assertThat(manual.outcome()).isEqualTo(Outcome.DELIVERED);
        assertThat(sent).hasSize(2);
    }

    @Test
    void aManualTriggerReArmsAFailedBatchWithTheSameIdAndItems() {
        script.add(DeliveryResult.rejected("HTTP 400"));
        service.runDueWork();
        store.eligible.put(OCTOBER, List.of(record())); // a late event: must NOT change the frozen batch
        clock.advance(Duration.ofHours(7));

        HrExportResult manual = service.triggerManually(OCTOBER);

        assertThat(manual.outcome()).isEqualTo(Outcome.DELIVERED);
        assertThat(manual.batch().triggerSource()).isEqualTo(TriggerSource.MANUAL);
        assertThat(manual.batch().attemptCount()).isEqualTo(2);
        assertThat(sent.get(1).batchUuid()).isEqualTo(sent.get(0).batchUuid());
        assertThat(sent.get(1).records()).hasSize(3);
    }

    @Test
    void anUnfinishedMonthCannotBeTriggeredAndNothingIsCreated() {
        clock.set(Instant.parse("2026-11-15T03:00:00Z"));

        assertThatThrownBy(() -> service.triggerManually(ExportPeriod.parse("2026-11")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not over");
        assertThat(store.find(ExportPeriod.parse("2026-11"))).isEmpty();
    }

    @Test
    void aBatchHeldByARunningAttemptIsReportedInProgressAndNotSentTwice() {
        HrExportBatch running = HrExportBatch.newBatch(OCTOBER, TriggerSource.SCHEDULED, RUN_AT, RUN_AT.plus(Duration.ofHours(5)))
                .startAttempt(RUN_AT, LEASE);
        store.put(running);

        HrExportResult manual = service.triggerManually(OCTOBER);

        assertThat(manual.outcome()).isEqualTo(Outcome.IN_PROGRESS);
        assertThat(sent).isEmpty();
    }

    @Test
    void anAttemptThatCrashedIsResumedOnceItsLeaseExpires() {
        store.put(HrExportBatch.newBatch(OCTOBER, TriggerSource.SCHEDULED, RUN_AT, RUN_AT.plus(Duration.ofHours(5)))
                .startAttempt(RUN_AT, LEASE));

        clock.advance(LEASE.minusSeconds(1));
        service.runDueWork();
        assertThat(sent).isEmpty();

        clock.advance(Duration.ofSeconds(1));
        service.runDueWork();
        assertThat(sent).hasSize(1);
        assertThat(store.find(OCTOBER).orElseThrow().attemptCount()).isEqualTo(2);
    }

    @Test
    void aChannelThatThrowsDespiteItsContractLeavesTheBatchRetryable() {
        HrExportService withThrowingChannel = new HrExportService(store, batch -> {
            throw new IllegalStateException("bug");
        }, alerts::add, new ExportSchedule(LocalTime.of(1, 0)),
                new ExportRetryPolicy(Duration.ofMinutes(1), Duration.ofMinutes(30), Duration.ofHours(5)),
                DataScope.productionOnly(), new HrExportSettings(LEASE, 10), clock);

        HrExportResult result = withThrowingChannel.runDueWork().get(0);

        assertThat(result.outcome()).isEqualTo(Outcome.RETRY_SCHEDULED);
        assertThat(result.batch().lastError()).contains("IllegalStateException");
    }

    private static PayrollRecord record() {
        return new PayrollRecord(UUID.randomUUID(), "EMP001", "BD", "BD-G01", "IN", Instant.parse("2026-10-10T01:00:00Z"));
    }

    /** A settable clock: the use case reads time only through {@link Clock}. */
    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void set(Instant instant) {
            this.now = instant;
        }

        void advance(Duration duration) {
            this.now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
