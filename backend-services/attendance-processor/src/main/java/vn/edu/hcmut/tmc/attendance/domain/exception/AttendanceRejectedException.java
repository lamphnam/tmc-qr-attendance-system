package vn.edu.hcmut.tmc.attendance.domain.exception;

/** Deterministic failure (constraint violation, value too long, ...). Retrying the same event cannot succeed. */
public class AttendanceRejectedException extends RuntimeException {

    public AttendanceRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
