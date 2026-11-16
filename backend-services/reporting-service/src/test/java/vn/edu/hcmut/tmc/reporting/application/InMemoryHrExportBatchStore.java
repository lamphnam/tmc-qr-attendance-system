package vn.edu.hcmut.tmc.reporting.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import vn.edu.hcmut.tmc.reporting.domain.model.ExportPeriod;
import vn.edu.hcmut.tmc.reporting.domain.model.HrExportBatch;
import vn.edu.hcmut.tmc.reporting.domain.model.PayrollRecord;
import vn.edu.hcmut.tmc.reporting.domain.model.PayrollSelection;
import vn.edu.hcmut.tmc.reporting.domain.port.IHrExportBatchStore;

/**
 * In-memory stand-in for the use-case tests. Faithful for the clauses the use case relies on: create-if-absent per
 * period, items frozen at creation, compare-and-set on (status, attemptCount). The SQL itself (time-zone window,
 * ALLOWED-only, fixture exclusion, concurrent creation) is covered against real PostgreSQL instead.
 */
class InMemoryHrExportBatchStore implements IHrExportBatchStore {

    /** What the "attendance table" would select for a period at creation time. */
    final Map<ExportPeriod, List<PayrollRecord>> eligible = new HashMap<>();
    private final Map<ExportPeriod, HrExportBatch> batches = new HashMap<>();
    private final Map<UUID, List<PayrollRecord>> frozen = new HashMap<>();

    @Override
    public synchronized HrExportBatch createIfAbsent(HrExportBatch candidate, PayrollSelection selection) {
        return batches.computeIfAbsent(candidate.period(), period -> {
            List<PayrollRecord> items = List.copyOf(eligible.getOrDefault(period, List.of()));
            frozen.put(candidate.batchUuid(), items);
            return withItems(candidate, items.size());
        });
    }

    @Override
    public synchronized Optional<HrExportBatch> find(ExportPeriod period) {
        return Optional.ofNullable(batches.get(period));
    }

    @Override
    public synchronized List<HrExportBatch> findDue(Instant now, int limit) {
        return batches.values().stream()
                .filter(b -> b.isDueAt(now))
                .sorted(Comparator.comparing(HrExportBatch::nextRetryAt))
                .limit(limit)
                .toList();
    }

    @Override
    public synchronized boolean compareAndSet(HrExportBatch expected, HrExportBatch next) {
        HrExportBatch stored = batches.get(expected.period());
        if (stored == null || stored.status() != expected.status() || stored.attemptCount() != expected.attemptCount()) {
            return false;
        }
        batches.put(next.period(), withItems(next, stored.itemCount()));
        return true;
    }

    @Override
    public synchronized List<PayrollRecord> loadRecords(UUID batchUuid) {
        return new ArrayList<>(frozen.getOrDefault(batchUuid, List.of()));
    }

    /** Test hook: put a batch in a given state, e.g. one whose attempt crashed. */
    synchronized void put(HrExportBatch batch) {
        batches.put(batch.period(), batch);
        frozen.putIfAbsent(batch.batchUuid(), List.of());
    }

    synchronized int size() {
        return batches.size();
    }

    private static HrExportBatch withItems(HrExportBatch b, int items) {
        return new HrExportBatch(b.batchUuid(), b.period(), b.status(), b.triggerSource(), b.createdAt(),
                b.retryDeadline(), b.attemptCount(), b.lastAttemptAt(), b.nextRetryAt(), b.lastError(), items);
    }
}
