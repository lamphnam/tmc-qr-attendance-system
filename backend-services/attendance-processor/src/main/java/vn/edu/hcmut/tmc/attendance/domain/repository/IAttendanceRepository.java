package vn.edu.hcmut.tmc.attendance.domain.repository;

import java.util.Optional;
import java.util.UUID;

import vn.edu.hcmut.tmc.attendance.domain.model.AttendanceRecord;

/** Port owned by the domain; the PostgreSQL implementation lives in infrastructure. */
public interface IAttendanceRepository {

    /**
     * Atomically inserts the record unless a row with the same {@code attendanceId} already exists.
     * Concurrency-safe: the database uniqueness constraint, not a prior read, decides.
     *
     * @return {@code true} if this call created the row, {@code false} if it already existed
     * @throws vn.edu.hcmut.tmc.attendance.domain.exception.AttendanceStorageException   transient failure - retry later
     * @throws vn.edu.hcmut.tmc.attendance.domain.exception.AttendanceRejectedException deterministic failure - never retry
     */
    boolean insertIfAbsent(AttendanceRecord record);

    Optional<AttendanceRecord> findById(UUID attendanceId);
}
