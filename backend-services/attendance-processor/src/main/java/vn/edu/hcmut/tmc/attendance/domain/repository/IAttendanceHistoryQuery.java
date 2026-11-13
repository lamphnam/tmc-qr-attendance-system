package vn.edu.hcmut.tmc.attendance.domain.repository;

import vn.edu.hcmut.tmc.attendance.domain.exception.AttendanceStorageException;
import vn.edu.hcmut.tmc.attendance.domain.model.AttendanceHistoryPage;
import vn.edu.hcmut.tmc.attendance.domain.model.AttendanceHistoryQuery;

/** Read-only history capability, separate from the ingestion repository. */
public interface IAttendanceHistoryQuery {

    /**
     * Finds facts matching BOTH employee and factory and the half-open time range, excluding dev fixtures.
     * Includes stored ALLOWED and REJECTED facts. Returns at most pageSize events, ordered by occurredAt ASC,
     * attendanceId ASC; hasNext reports a further matching row at query time. No writes or external calls.
     * No total-count or cross-request snapshot is promised: offset pages can shift if new facts arrive.
     * Scope filtering does not authorize the caller; authorization is required before invoking this port.
     *
     * @param query non-null validated query with mandatory employee/factory scope
     * @return non-null immutable page; empty only when the requested page contains no matching events
     * @throws IllegalArgumentException if query is null
     * @throws AttendanceStorageException if storage cannot answer; never substitute an empty page for a failure
     */
    AttendanceHistoryPage find(AttendanceHistoryQuery query);
}
