package vn.edu.hcmut.tmc.reporting.domain.port;

import vn.edu.hcmut.tmc.reporting.domain.model.DeliveryResult;
import vn.edu.hcmut.tmc.reporting.domain.model.PayrollBatch;

/**
 * A channel that hands one frozen batch to HR/Payroll (ADR-03). The export use case depends only on this contract;
 * which channel runs is chosen by {@code tmc.hr-export.channel}. Every implementation must pass
 * {@code HrPayrollGatewayContract}:
 *
 * <ul>
 *   <li><b>Precondition.</b> {@code batch} is not null. An empty batch is valid: a month with no scans is still
 *       reported, so the receiver can tell "nothing happened" from "nothing arrived".</li>
 *   <li><b>Idempotent.</b> Sending a batch whose {@code batchUuid} the receiver already holds succeeds again, reports
 *       {@code duplicateBatch = true} and never duplicates a record at the receiver.</li>
 *   <li><b>No false confirmation.</b> {@code ACKNOWLEDGED} only when the receiver itself confirmed the batch; a complete
 *       hand-over without confirmation is {@code DELIVERED}.</li>
 *   <li><b>Transient failure</b> (receiver down, timeout, I/O error) is {@code RETRYABLE} and leaves no partial batch
 *       behind, so resending the same batch is always safe.</li>
 *   <li><b>{@code REJECTED}</b> only when the receiver refuses the content and a resend cannot succeed.</li>
 *   <li><b>Never throws</b> for any of the above, and has no side effect other than the hand-over: it does not touch
 *       the central database. {@code detail} is a short diagnostic, never a payload.</li>
 * </ul>
 */
public interface IHrPayrollGateway {

    DeliveryResult send(PayrollBatch batch);
}
