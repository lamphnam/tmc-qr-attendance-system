package vn.edu.hcmut.tmc.attendance.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

import vn.edu.hcmut.tmc.attendance.application.service.AttendanceIngestionService;
import vn.edu.hcmut.tmc.attendance.application.service.IngestionResult;
import vn.edu.hcmut.tmc.attendance.application.service.IngestionResult.Status;
import vn.edu.hcmut.tmc.attendance.domain.exception.AttendanceRejectedException;
import vn.edu.hcmut.tmc.attendance.domain.exception.AttendanceStorageException;
import vn.edu.hcmut.tmc.attendance.domain.model.AttendanceRecord;
import vn.edu.hcmut.tmc.attendance.domain.model.Direction;
import vn.edu.hcmut.tmc.attendance.domain.model.ScanResult;
import vn.edu.hcmut.tmc.attendance.domain.repository.IAttendanceRepository;
import vn.edu.hcmut.tmc.attendance.domain.service.IdempotencyChecker;

/** Pure application-logic test: the repository is an in-memory fake, the real SQL is covered by the PostgreSQL IT. */
class AttendanceIngestionServiceTest {

    private static final Instant AT = Instant.parse("2026-11-01T01:20:00Z");

    private FakeRepository repository;
    private AttendanceIngestionService service;

    @BeforeEach
    void setUp() {
        repository = new FakeRepository();
        service = new AttendanceIngestionService(repository, new IdempotencyChecker(), TransactionOperations.withoutTransaction());
    }

    private static AttendanceRecord event(UUID id, String employeeId) {
        return new AttendanceRecord(id, employeeId, "BD", "BD-G01", Direction.IN, AT, ScanResult.ALLOWED, null, true);
    }

    @Test
    void firstSendIsStoredAndRetryIsAcknowledgedWithoutSecondRow() {
        UUID id = UUID.randomUUID();

        assertThat(service.ingest(event(id, "EMP001")).status()).isEqualTo(Status.STORED);
        assertThat(service.ingest(event(id, "EMP001")).status()).isEqualTo(Status.ALREADY_EXISTS);

        assertThat(repository.rows).hasSize(1);
    }

    @Test
    void sameIdWithDifferentPayloadIsAConflictAndNeverOverwrites() {
        UUID id = UUID.randomUUID();
        service.ingest(event(id, "EMP001"));

        IngestionResult result = service.ingest(event(id, "EMP999"));

        assertThat(result.status()).isEqualTo(Status.CONFLICT);
        assertThat(repository.rows.get(id).employeeId()).isEqualTo("EMP001");
    }

    @Test
    void storageOutageIsRetryableAndStoresNothing() {
        repository.failWith = new AttendanceStorageException("db down");

        IngestionResult result = service.ingest(event(UUID.randomUUID(), "EMP001"));

        assertThat(result.status()).isEqualTo(Status.RETRYABLE);
        assertThat(repository.rows).isEmpty();
    }

    @Test
    void failureToOpenTheTransactionIsAlsoRetryable() {
        AttendanceIngestionService unreachableDb = new AttendanceIngestionService(repository, new IdempotencyChecker(),
                new TransactionOperations() {
                    @Override
                    public <T> T execute(org.springframework.transaction.support.TransactionCallback<T> action) {
                        throw new org.springframework.transaction.CannotCreateTransactionException("cannot open JDBC connection");
                    }
                });

        IngestionResult result = unreachableDb.ingest(event(UUID.randomUUID(), "EMP001"));

        assertThat(result.status()).isEqualTo(Status.RETRYABLE);
    }

    @Test
    void constraintViolationIsPermanentlyRejected() {
        repository.failWith = new AttendanceRejectedException("too long", null);

        assertThat(service.ingest(event(UUID.randomUUID(), "EMP001")).status()).isEqualTo(Status.REJECTED);
    }

    @Test
    void batchAcksEachEventIndependently() {
        UUID dup = UUID.randomUUID();
        service.ingest(event(dup, "EMP001"));

        List<IngestionResult> results = service.ingestBatch(List.of(
                event(UUID.randomUUID(), "EMP002"),
                event(dup, "EMP001"),
                event(dup, "EMP777")));

        assertThat(results).extracting(IngestionResult::status)
                .containsExactly(Status.STORED, Status.ALREADY_EXISTS, Status.CONFLICT);
        assertThat(repository.rows).hasSize(2);
    }

    private static final class FakeRepository implements IAttendanceRepository {
        final Map<UUID, AttendanceRecord> rows = new ConcurrentHashMap<>();
        RuntimeException failWith;

        @Override
        public boolean insertIfAbsent(AttendanceRecord record) {
            if (failWith != null) {
                throw failWith;
            }
            return rows.putIfAbsent(record.attendanceId(), record) == null;
        }

        @Override
        public Optional<AttendanceRecord> findById(UUID attendanceId) {
            return Optional.ofNullable(rows.get(attendanceId));
        }
    }
}
