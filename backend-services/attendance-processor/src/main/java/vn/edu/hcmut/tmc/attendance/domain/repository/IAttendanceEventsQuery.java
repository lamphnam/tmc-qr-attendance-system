package vn.edu.hcmut.tmc.attendance.domain.repository;

import java.util.List;

import vn.edu.hcmut.tmc.attendance.domain.exception.AttendanceStorageException;
import vn.edu.hcmut.tmc.attendance.domain.model.AttendanceRecord;

/** Bounded inspection capability for synthetic dev data, separate from fixture-excluding employee history. */
public interface IAttendanceEventsQuery {

    /**
     * Returns up to limit facts, INCLUDING dev fixtures and both scan results, ordered by occurredAt DESC,
     * attendanceId DESC. employeeId is an optional exact filter, not authorization; all factories are visible.
     * Performs no writes. No total count, aggregation or cross-request snapshot is promised.
     *
     * @param limit validated bound in 1..100
     * @param employeeId null for all employees, otherwise a normalized nonblank ID of at most 32 characters
     * @return non-null immutable list, empty when nothing matches
     * @throws AttendanceStorageException if storage cannot answer; never substitute a successful empty result
     */
    List<AttendanceRecord> findRecent(int limit, String employeeId);
}
