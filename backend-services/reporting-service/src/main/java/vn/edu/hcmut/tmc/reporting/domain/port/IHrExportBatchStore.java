package vn.edu.hcmut.tmc.reporting.domain.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import vn.edu.hcmut.tmc.reporting.domain.exception.ReportingStorageException;
import vn.edu.hcmut.tmc.reporting.domain.model.ExportPeriod;
import vn.edu.hcmut.tmc.reporting.domain.model.HrExportBatch;
import vn.edu.hcmut.tmc.reporting.domain.model.PayrollRecord;
import vn.edu.hcmut.tmc.reporting.domain.model.PayrollSelection;

/**
 * Durable batch state, used only by the export use case. Read-only consumers (the dashboard) use
 * {@link IHrExportStatusReader} instead, so they cannot create or change a batch. Every method throws
 * {@link ReportingStorageException} when the database is unreachable.
 */
public interface IHrExportBatchStore {

    /**
     * Creates {@code candidate} and freezes its items from {@code selection} in one transaction, unless a batch for the
     * period already exists. Concurrent callers for one period end up with exactly one batch.
     *
     * @return the stored batch: the new one, or the existing one untouched
     */
    HrExportBatch createIfAbsent(HrExportBatch candidate, PayrollSelection selection);

    Optional<HrExportBatch> find(ExportPeriod period);

    /** Batches whose retry or expired lease is due at {@code now}, oldest first. */
    List<HrExportBatch> findDue(Instant now, int limit);

    /**
     * Writes {@code next} only if the stored row still has {@code expected}'s status and attempt count.
     *
     * @return false when another attempt changed the row first; nothing is written then
     */
    boolean compareAndSet(HrExportBatch expected, HrExportBatch next);

    /** The frozen items of a batch, in a stable order. */
    List<PayrollRecord> loadRecords(UUID batchUuid);
}
