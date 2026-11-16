package vn.edu.hcmut.tmc.userqr.domain.exception;

public class QrRevokedException extends RuntimeException {
    public QrRevokedException() { super("Employee QR has been revoked"); }
}
