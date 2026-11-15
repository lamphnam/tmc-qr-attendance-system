package vn.edu.hcmut.tmc.reporting.infrastructure.hr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import vn.edu.hcmut.tmc.reporting.domain.model.DeliveryOutcome;
import vn.edu.hcmut.tmc.reporting.domain.model.DeliveryResult;
import vn.edu.hcmut.tmc.reporting.domain.model.ExportPeriod;
import vn.edu.hcmut.tmc.reporting.domain.model.PayrollBatch;
import vn.edu.hcmut.tmc.reporting.domain.model.PayrollRecord;
import vn.edu.hcmut.tmc.reporting.domain.port.IHrPayrollGateway;

/**
 * The behavioural contract of {@link IHrPayrollGateway} (see its Javadoc), run unchanged against every implementation.
 * A subclass only says how to reach its receiver and how to make that receiver unavailable; the assertions are shared.
 * This is the LSP evidence: {@code HrExportService} can take any channel that passes these tests.
 */
abstract class HrPayrollGatewayContract {

    /** The channel under test, pointed at a receiver that the subclass controls. */
    protected abstract IHrPayrollGateway channel();

    /** Makes the receiver transiently unavailable (HR down, drop directory unusable). */
    protected abstract void breakReceiver() throws Exception;

    protected abstract void repairReceiver() throws Exception;

    /** Every attendance id the receiver holds, one entry per stored copy, so a duplicate would show twice. */
    protected abstract List<UUID> heldByReceiver() throws Exception;

    /** Whether this receiver confirms what it got (an API does, a file drop does not). */
    protected abstract boolean receiverConfirms();

    private DeliveryOutcome expectedSuccess() {
        return receiverConfirms() ? DeliveryOutcome.ACKNOWLEDGED : DeliveryOutcome.DELIVERED;
    }

    @Test
    void aBatchArrivesCompletelyAndSuccessIsReportedHonestly() throws Exception {
        PayrollBatch batch = batch("2025-01", 3);

        DeliveryResult result = channel().send(batch);

        assertThat(result.outcome()).isEqualTo(expectedSuccess());
        assertThat(result.duplicateBatch()).isFalse();
        assertThat(heldByReceiver()).containsExactlyInAnyOrderElementsOf(ids(batch));
    }

    @Test
    void resendingTheSameBatchSucceedsAsADuplicateAndStoresNothingTwice() throws Exception {
        PayrollBatch batch = batch("2025-02", 2);
        channel().send(batch);

        DeliveryResult again = channel().send(batch);

        assertThat(again.outcome()).isEqualTo(expectedSuccess());
        assertThat(again.duplicateBatch()).isTrue();
        assertThat(heldByReceiver()).containsExactlyInAnyOrderElementsOf(ids(batch));
    }

    @Test
    void anEmptyMonthIsStillDeliveredNotRejected() throws Exception {
        DeliveryResult result = channel().send(batch("2025-03", 0));

        assertThat(result.outcome()).isEqualTo(expectedSuccess());
        assertThat(heldByReceiver()).isEmpty();
    }

    @Test
    void anUnavailableReceiverIsRetryableNeverThrownAndLeavesNoPartialBatch() throws Exception {
        PayrollBatch batch = batch("2025-04", 2);
        breakReceiver();

        DeliveryResult[] result = new DeliveryResult[1];
        assertThatCode(() -> result[0] = channel().send(batch)).doesNotThrowAnyException();

        assertThat(result[0].outcome()).isEqualTo(DeliveryOutcome.RETRYABLE);
        assertThat(result[0].detail()).isNotBlank().doesNotContain("EMP-CONTRACT");
        repairReceiver();
        assertThat(heldByReceiver()).isEmpty();
    }

    @Test
    void afterTheReceiverRecoversTheSameBatchGoesThroughAsNew() throws Exception {
        PayrollBatch batch = batch("2025-05", 2);
        breakReceiver();
        channel().send(batch);
        repairReceiver();

        DeliveryResult result = channel().send(batch);

        assertThat(result.outcome()).isEqualTo(expectedSuccess());
        assertThat(result.duplicateBatch()).as("a failed attempt must not have left part of the batch behind").isFalse();
        assertThat(heldByReceiver()).containsExactlyInAnyOrderElementsOf(ids(batch));
    }

    static PayrollBatch batch(String period, int size) {
        ExportPeriod p = ExportPeriod.parse(period);
        List<PayrollRecord> records = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            records.add(new PayrollRecord(UUID.randomUUID(), "EMP-CONTRACT-" + i, "BD", "BD-G01", i % 2 == 0 ? "IN" : "OUT",
                    p.startInclusive().plusSeconds(3600L * (i + 1))));
        }
        return new PayrollBatch(p.batchUuid(), p, records);
    }

    static List<UUID> ids(PayrollBatch batch) {
        return batch.records().stream().map(PayrollRecord::attendanceId).toList();
    }
}
