package vn.edu.hcmut.tmc.attendance.domain.service;

import vn.edu.hcmut.tmc.attendance.domain.model.AttendanceRecord;

/**
 * Decides what a repeated {@code attendanceId} means. The database unique key guarantees there is only
 * ever one row; this class only classifies the retry: a harmless re-send of the same fact, or an
 * attempt to store a different fact under an already used id (which must never overwrite silently).
 */
public class IdempotencyChecker {

    public enum Verdict {
        /** Same id, same business payload: a retry (e.g. the ack was lost). Safe to acknowledge. */
        SAME_PAYLOAD,
        /** Same id, different payload: collision. Do not overwrite, do not acknowledge as stored. */
        CONFLICT
    }

    public Verdict compare(AttendanceRecord existing, AttendanceRecord incoming) {
        return existing.samePayloadAs(incoming) ? Verdict.SAME_PAYLOAD : Verdict.CONFLICT;
    }
}
