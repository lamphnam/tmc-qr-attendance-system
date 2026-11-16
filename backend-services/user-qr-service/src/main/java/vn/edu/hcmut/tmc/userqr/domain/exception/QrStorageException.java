package vn.edu.hcmut.tmc.userqr.domain.exception;

public class QrStorageException extends RuntimeException {
    public QrStorageException(Throwable cause) { super("QR storage is unavailable", cause); }
}
