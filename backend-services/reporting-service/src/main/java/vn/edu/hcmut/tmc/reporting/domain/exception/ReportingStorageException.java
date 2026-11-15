package vn.edu.hcmut.tmc.reporting.domain.exception;

/** The central database could not be reached; the caller may retry later (HTTP 503 at the edge). */
public class ReportingStorageException extends RuntimeException {

    public ReportingStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
