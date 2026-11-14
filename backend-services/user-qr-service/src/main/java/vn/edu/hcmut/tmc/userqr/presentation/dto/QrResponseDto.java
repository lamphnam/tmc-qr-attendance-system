package vn.edu.hcmut.tmc.userqr.presentation.dto;

import java.time.Instant;
import vn.edu.hcmut.tmc.userqr.application.service.QrIssue;
import vn.edu.hcmut.tmc.userqr.domain.model.QrStatus;

public record QrResponseDto(String employeeId, String fullName, String factoryId, QrStatus qrStatus,
                             String totpCode, long timeStep, Instant issuedAt, Instant expiresAt,
                             long remainingSeconds, int secretVersion, String qrTokenPayload) {
    public static QrResponseDto from(QrIssue qr) {
        return new QrResponseDto(qr.employeeId(), qr.fullName(), qr.factoryId(), qr.qrStatus(), qr.totpCode(),
                qr.timeStep(), qr.issuedAt(), qr.expiresAt(), qr.remainingSeconds(), qr.secretVersion(), qr.qrTokenPayload());
    }

    @Override
    public String toString() {
        return "QrResponseDto[employeeId=" + employeeId + ", totpCode=[REDACTED], qrTokenPayload=[REDACTED]]";
    }
}
