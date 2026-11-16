package vn.edu.hcmut.tmc.userqr.application.service;

import java.time.Instant;
import vn.edu.hcmut.tmc.userqr.domain.model.QrStatus;

public record QrIssue(String employeeId, String fullName, String factoryId, QrStatus qrStatus,
                      String totpCode, long timeStep, Instant issuedAt, Instant expiresAt,
                      long remainingSeconds, int secretVersion, String qrTokenPayload) {
    @Override
    public String toString() {
        return "QrIssue[employeeId=" + employeeId + ", totpCode=[REDACTED], qrTokenPayload=[REDACTED]]";
    }
}
