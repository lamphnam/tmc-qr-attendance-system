package vn.edu.hcmut.tmc.attendance.application.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionOperations;

import vn.edu.hcmut.tmc.attendance.domain.exception.AttendanceRejectedException;
import vn.edu.hcmut.tmc.attendance.domain.exception.AttendanceStorageException;
import vn.edu.hcmut.tmc.attendance.domain.model.AttendanceRecord;
import vn.edu.hcmut.tmc.attendance.domain.repository.IAttendanceRepository;
import vn.edu.hcmut.tmc.attendance.domain.service.IdempotencyChecker;

/**
 * Use case: persist attendance events idempotently.
 *
 * <p>Every event is committed in its <em>own</em> transaction. A result of {@code STORED} or
 * {@code ALREADY_EXISTS} is therefore only ever returned after the row is durably committed, and one
 * bad event in a batch can never roll back or hide its siblings (per-event ack, ADR-01).
 */
@Service
public class AttendanceIngestionService {

    private static final Logger log = LoggerFactory.getLogger(AttendanceIngestionService.class);

    private final IAttendanceRepository repository;
    private final IdempotencyChecker idempotencyChecker;
    private final TransactionOperations transactions;

    public AttendanceIngestionService(IAttendanceRepository repository,
                                      IdempotencyChecker idempotencyChecker,
                                      TransactionOperations transactions) {
        this.repository = repository;
        this.idempotencyChecker = idempotencyChecker;
        this.transactions = transactions;
    }

    public IngestionResult ingest(AttendanceRecord record) {
        String id = record.attendanceId().toString();
        try {
            return transactions.execute(status -> storeOrClassify(record));
        } catch (AttendanceRejectedException e) {
            log.warn("attendanceId={} rejected permanently: {}", id, e.getMessage());
            return IngestionResult.rejected(id, e.getMessage());
        } catch (AttendanceStorageException | TransactionException e) {
            // TransactionException: the DB is unreachable so the transaction itself could not be opened -
            // that happens before the repository runs, so it is never translated to AttendanceStorageException.
            log.warn("attendanceId={} not stored, central storage unavailable: {}", id, e.getClass().getSimpleName());
            return IngestionResult.retryable(id, "central storage unavailable");
        }
    }

    public List<IngestionResult> ingestBatch(List<AttendanceRecord> records) {
        List<IngestionResult> results = new ArrayList<>(records.size());
        for (AttendanceRecord record : records) {
            results.add(ingest(record));
        }
        return results;
    }

    /** @throws AttendanceStorageException if the database cannot be reached */
    public Optional<AttendanceRecord> find(UUID attendanceId) {
        return repository.findById(attendanceId);
    }

    private IngestionResult storeOrClassify(AttendanceRecord record) {
        String id = record.attendanceId().toString();
        if (repository.insertIfAbsent(record)) {
            return IngestionResult.stored(id);
        }
        AttendanceRecord existing = repository.findById(record.attendanceId())
                .orElseThrow(() -> new AttendanceStorageException("row reported as existing but could not be read back"));
        return switch (idempotencyChecker.compare(existing, record)) {
            case SAME_PAYLOAD -> IngestionResult.alreadyExists(id);
            case CONFLICT -> IngestionResult.conflict(id);
        };
    }
}
