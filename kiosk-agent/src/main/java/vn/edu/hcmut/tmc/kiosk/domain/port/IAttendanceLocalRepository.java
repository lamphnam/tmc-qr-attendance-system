package vn.edu.hcmut.tmc.kiosk.domain.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import vn.edu.hcmut.tmc.kiosk.domain.model.GateCommandStatus;
import vn.edu.hcmut.tmc.kiosk.domain.model.LocalAttendanceEvent;
import vn.edu.hcmut.tmc.kiosk.domain.model.QueueCounts;

/** Durable local queue port. The SQLite implementation lives in infrastructure. */
public interface IAttendanceLocalRepository {

    /**
     * Durably commits a new PENDING event. When this returns the row has been committed to storage; only then may
     * the caller send the open-gate command (ADR-01).
     */
    void insertPending(LocalAttendanceEvent event);

    void updateGateCommandStatus(UUID attendanceId, GateCommandStatus status, String detail);

    /** PENDING events that are due now (no retry scheduled, or the scheduled time has passed), oldest first. */
    List<LocalAttendanceEvent> findDueForSync(Instant now, int limit);

    /** @return true only if a PENDING row was transitioned; acks never resurrect FAILED or re-mark SYNCED rows */
    boolean markSynced(UUID attendanceId, Instant syncedAt);

    /** @return true only if a PENDING row was transitioned */
    boolean markFailed(UUID attendanceId, String reason);

    /** Keeps the row PENDING, increments the retry counter and records when to try again. */
    boolean scheduleRetry(UUID attendanceId, Instant nextRetryAt, String reason);

    Optional<LocalAttendanceEvent> findById(UUID attendanceId);

    List<LocalAttendanceEvent> findRecent(int limit);

    QueueCounts countByStatus();
}
