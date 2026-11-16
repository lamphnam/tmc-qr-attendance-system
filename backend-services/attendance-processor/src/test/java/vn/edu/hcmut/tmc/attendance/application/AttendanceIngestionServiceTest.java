package vn.edu.hcmut.tmc.attendance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

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

    @Test
    void repeatedIdWithinOneBatchDistinguishesRetryFromConflictAndPreservesTheFirstPayload() {
        UUID repeatedId = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();
        AttendanceRecord first = event(repeatedId, "EMP001");

        List<IngestionResult> results = service.ingestBatch(List.of(first, event(repeatedId, "EMP001"),
                event(repeatedId, "EMP999"), event(otherId, "EMP002"), event(repeatedId, "EMP001")));

        assertThat(results).extracting(IngestionResult::status)
                .containsExactly(Status.STORED, Status.ALREADY_EXISTS, Status.CONFLICT, Status.STORED, Status.ALREADY_EXISTS);
        assertThat(repository.rows).hasSize(2).containsEntry(repeatedId, first).containsEntry(otherId, event(otherId, "EMP002"));
    }

    @Test
    void oneStorageFailureDoesNotHideSuccessfulEventsBeforeOrAfterIt() {
        IAttendanceRepository storage = mock(IAttendanceRepository.class);
        UUID before = UUID.randomUUID();
        UUID failed = UUID.randomUUID();
        UUID after = UUID.randomUUID();
        when(storage.insertIfAbsent(any())).thenAnswer(call -> {
            AttendanceRecord record = call.getArgument(0);
            if (record.attendanceId().equals(failed)) {
                throw new AttendanceStorageException("synthetic outage");
            }
            return true;
        });
        AttendanceIngestionService partiallyAvailable = new AttendanceIngestionService(storage, new IdempotencyChecker(),
                TransactionOperations.withoutTransaction());

        List<IngestionResult> results = partiallyAvailable.ingestBatch(List.of(event(before, "EMP001"),
                event(failed, "EMP002"), event(after, "EMP003")));

        assertThat(results).extracting(IngestionResult::status).containsExactly(Status.STORED, Status.RETRYABLE, Status.STORED);
        assertThat(results).extracting(IngestionResult::attendanceId).containsExactly(before.toString(), failed.toString(), after.toString());
        verify(storage, times(3)).insertIfAbsent(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void commitFailureAfterTheCallbackNeverReturnsStoredOrDuplicateAck(boolean inserted) {
        AttendanceRecord record = event(UUID.randomUUID(), "EMP001");
        IAttendanceRepository storage = mock(IAttendanceRepository.class);
        when(storage.insertIfAbsent(record)).thenReturn(inserted);
        when(storage.findById(record.attendanceId())).thenReturn(Optional.of(record));
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        TransactionStatus transaction = mock(TransactionStatus.class);
        when(manager.getTransaction(any())).thenReturn(transaction);
        doThrow(new TransactionSystemException("synthetic commit failure")).when(manager).commit(transaction);
        AttendanceIngestionService failingCommit = new AttendanceIngestionService(storage, new IdempotencyChecker(),
                new TransactionTemplate(manager));

        IngestionResult result = failingCommit.ingest(record);

        assertThat(result.status()).isEqualTo(Status.RETRYABLE);
        assertThat(result.attendanceId()).isEqualTo(record.attendanceId().toString());
        var order = inOrder(storage, manager);
        order.verify(manager).getTransaction(any());
        order.verify(storage).insertIfAbsent(record);
        if (!inserted) {
            order.verify(storage).findById(record.attendanceId());
        }
        order.verify(manager).commit(transaction);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void failedOrMissingDuplicateReadbackIsRetryableInsteadOfAnAlreadyExistsAck(boolean readFails) {
        AttendanceRecord record = event(UUID.randomUUID(), "EMP001");
        IAttendanceRepository storage = mock(IAttendanceRepository.class);
        when(storage.insertIfAbsent(record)).thenReturn(false);
        if (readFails) {
            when(storage.findById(record.attendanceId())).thenThrow(new AttendanceStorageException("synthetic read failure"));
        } else {
            when(storage.findById(record.attendanceId())).thenReturn(Optional.empty());
        }
        AttendanceIngestionService failingRead = new AttendanceIngestionService(storage, new IdempotencyChecker(),
                TransactionOperations.withoutTransaction());

        assertThat(failingRead.ingest(record).status()).isEqualTo(Status.RETRYABLE);
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
