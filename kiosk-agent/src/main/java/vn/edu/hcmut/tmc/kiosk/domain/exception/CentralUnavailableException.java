package vn.edu.hcmut.tmc.kiosk.domain.exception;

/**
 * The whole sync request failed (network, timeout, HTTP error). Nothing is known to be committed, so every
 * event in the request stays PENDING and is retried with the same ids.
 */
public class CentralUnavailableException extends RuntimeException {

    public CentralUnavailableException(String message) {
        super(message);
    }

    public CentralUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
