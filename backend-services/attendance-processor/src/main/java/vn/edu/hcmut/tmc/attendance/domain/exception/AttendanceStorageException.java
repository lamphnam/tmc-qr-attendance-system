package vn.edu.hcmut.tmc.attendance.domain.exception;

/** Transient persistence failure (DB down, timeout, ...). The caller should retry the same event id later. */
public class AttendanceStorageException extends RuntimeException {

    public AttendanceStorageException(String message, Throwable cause) {
        super(message, cause);
    }

    public AttendanceStorageException(String message) {
        super(message);
    }
}
