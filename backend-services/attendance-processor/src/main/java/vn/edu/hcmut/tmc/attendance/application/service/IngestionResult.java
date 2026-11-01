package vn.edu.hcmut.tmc.attendance.application.service;

/**
 * Outcome of ingesting one event. {@code attendanceId} is the id as received (a String so that even a
 * malformed id can be echoed back in a per-event rejection).
 */
public record IngestionResult(String attendanceId, Status status, String reason) {

    public enum Status {
        /** Row created and committed by this call. */
        STORED,
        /** Row already existed with the same payload (retry) - nothing written. */
        ALREADY_EXISTS,
        /** Row exists with a different payload - nothing written, permanent. */
        CONFLICT,
        /** Event is invalid / violates constraints - permanent. */
        REJECTED,
        /** Central could not persist right now - sender must retry the same id. */
        RETRYABLE
    }

    public static IngestionResult stored(String id) {
        return new IngestionResult(id, Status.STORED, null);
    }

    public static IngestionResult alreadyExists(String id) {
        return new IngestionResult(id, Status.ALREADY_EXISTS, null);
    }

    public static IngestionResult conflict(String id) {
        return new IngestionResult(id, Status.CONFLICT, "attendanceId already stored with a different payload");
    }

    public static IngestionResult rejected(String id, String reason) {
        return new IngestionResult(id, Status.REJECTED, reason);
    }

    public static IngestionResult retryable(String id, String reason) {
        return new IngestionResult(id, Status.RETRYABLE, reason);
    }
}
