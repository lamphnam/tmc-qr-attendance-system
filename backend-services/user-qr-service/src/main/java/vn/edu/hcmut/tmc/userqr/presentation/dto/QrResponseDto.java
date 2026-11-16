package vn.edu.hcmut.tmc.userqr.presentation.dto;

import java.time.Instant;
import vn.edu.hcmut.tmc.userqr.application.service.QrIssue;
import vn.edu.hcmut.tmc.userqr.domain.model.QrStatus;
import vn.edu.hcmut.tmc.userqr.domain.service.TotpCodeGenerator;

public record QrResponseDto(String employeeId, String fullName, String factoryId, QrStatus qrStatus,
                             String totpCode, long timeStep, Instant issuedAt, Instant expiresAt,
                             long remainingSeconds, int secretVersion, String qrTokenPayload,
                             int periodSeconds, QrPayload qrPayload) {
    public record QrPayload(String empId, String totp, long ts, int ver) {
        @Override
        public String toString() { return "QrPayload[empId=" + empId + ", totp=[REDACTED], ts=" + ts + ", ver=" + ver + "]"; }
    }

    public static QrResponseDto from(QrIssue qr) {
        return new QrResponseDto(qr.employeeId(), qr.fullName(), qr.factoryId(), qr.qrStatus(), qr.totpCode(),
                qr.timeStep(), qr.issuedAt(), qr.expiresAt(), qr.remainingSeconds(), qr.secretVersion(), qr.qrTokenPayload(),
                Math.toIntExact(TotpCodeGenerator.TIME_STEP_SECONDS),
                new QrPayload(qr.employeeId(), qr.totpCode(), qr.issuedAt().getEpochSecond(), qr.secretVersion()));
    }

    @Override
    public String toString() {
        return "QrResponseDto[employeeId=" + employeeId + ", totpCode=[REDACTED], qrTokenPayload=[REDACTED]]";
    }
}
