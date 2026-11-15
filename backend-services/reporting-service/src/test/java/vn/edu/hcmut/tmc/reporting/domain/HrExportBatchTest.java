package vn.edu.hcmut.tmc.reporting.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import vn.edu.hcmut.tmc.reporting.domain.model.BatchStatus;
import vn.edu.hcmut.tmc.reporting.domain.model.ExportPeriod;
import vn.edu.hcmut.tmc.reporting.domain.model.HrExportBatch;
import vn.edu.hcmut.tmc.reporting.domain.model.TriggerSource;

/** The batch state machine: legal transitions, illegal ones throw, the lease expires. */
class HrExportBatchTest {

    private static final Instant T0 = Instant.parse("2026-10-31T18:00:00Z");
    private static final Duration LEASE = Duration.ofMinutes(2);
    private static final ExportPeriod OCTOBER = ExportPeriod.parse("2026-10");

    private final HrExportBatch fresh = HrExportBatch.newBatch(OCTOBER, TriggerSource.SCHEDULED, T0, T0.plus(Duration.ofHours(5)));

    @Test
    void aNewBatchIsPendingDueNowAndCarriesThePeriodsId() {
        assertThat(fresh.status()).isEqualTo(BatchStatus.PENDING);
        assertThat(fresh.batchUuid()).isEqualTo(OCTOBER.batchUuid());
        assertThat(fresh.isDueAt(T0)).isTrue();
        assertThat(fresh.attemptCount()).isZero();
    }

    @Test
    void anAttemptHoldsALeaseThatExpires() {
        HrExportBatch sending = fresh.startAttempt(T0, LEASE);

        assertThat(sending.status()).isEqualTo(BatchStatus.SENDING);
        assertThat(sending.attemptCount()).isEqualTo(1);
        assertThat(sending.isLeaseHeldAt(T0.plusSeconds(119))).isTrue();
        assertThat(sending.isDueAt(T0.plusSeconds(119))).isFalse();
        assertThat(sending.isDueAt(T0.plus(LEASE))).isTrue();
        assertThatThrownBy(() -> sending.startAttempt(T0.plusSeconds(60), LEASE)).isInstanceOf(IllegalStateException.class);
        assertThat(sending.startAttempt(T0.plus(LEASE), LEASE).attemptCount()).isEqualTo(2);
    }

    @Test
    void anAttemptEndsInExactlyOneOfFourStates() {
        HrExportBatch sending = fresh.startAttempt(T0, LEASE);

        assertThat(sending.acknowledged().status()).isEqualTo(BatchStatus.ACKNOWLEDGED);
        assertThat(sending.sent().status()).isEqualTo(BatchStatus.SENT);
        HrExportBatch retry = sending.retryAt(T0.plusSeconds(60), "HTTP 503");
        assertThat(retry.status()).isEqualTo(BatchStatus.PENDING);
        assertThat(retry.nextRetryAt()).isEqualTo(T0.plusSeconds(60));
        assertThat(retry.lastError()).isEqualTo("HTTP 503");
        HrExportBatch failed = sending.failed("HTTP 400");
        assertThat(failed.status()).isEqualTo(BatchStatus.FAILED);
        assertThat(failed.nextRetryAt()).isNull();
        assertThat(failed.isDueAt(T0.plus(Duration.ofDays(1)))).isFalse();
    }

    @Test
    void deliveredBatchesCannotBeAttemptedOrReArmed() {
        HrExportBatch acknowledged = fresh.startAttempt(T0, LEASE).acknowledged();

        assertThatThrownBy(() -> acknowledged.startAttempt(T0, LEASE)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> acknowledged.rearm(T0, T0, TriggerSource.MANUAL)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(fresh::acknowledged).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void reArmingAFailedBatchKeepsItsIdAndAttemptsAndOpensANewWindow() {
        HrExportBatch failed = fresh.startAttempt(T0, LEASE).failed("deadline");
        Instant later = T0.plus(Duration.ofHours(6));

        HrExportBatch rearmed = failed.rearm(later, later.plus(Duration.ofHours(5)), TriggerSource.MANUAL);

        assertThat(rearmed.batchUuid()).isEqualTo(fresh.batchUuid());
        assertThat(rearmed.status()).isEqualTo(BatchStatus.PENDING);
        assertThat(rearmed.attemptCount()).isEqualTo(1);
        assertThat(rearmed.triggerSource()).isEqualTo(TriggerSource.MANUAL);
        assertThat(rearmed.retryDeadline()).isEqualTo(later.plus(Duration.ofHours(5)));
        assertThat(rearmed.isDueAt(later)).isTrue();
    }

    @Test
    void aBatchIdNotDerivedFromItsPeriodIsRefused() {
        assertThatThrownBy(() -> new HrExportBatch(UUID.randomUUID(), OCTOBER, BatchStatus.PENDING, TriggerSource.MANUAL,
                T0, T0, 0, null, T0, null, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
