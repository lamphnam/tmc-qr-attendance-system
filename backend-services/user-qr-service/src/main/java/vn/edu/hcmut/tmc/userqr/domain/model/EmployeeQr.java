package vn.edu.hcmut.tmc.userqr.domain.model;

import java.time.Instant;

public record EmployeeQr(String employeeId, String fullName, String factoryId, String department,
                         String role, String sharedSecret, QrStatus qrStatus, int secretVersion,
                         Instant updatedAt) {
    public EmployeeQr changeQr(String secret, QrStatus status, Instant now) {
        return new EmployeeQr(employeeId, fullName, factoryId, department, role, secret, status,
                Math.incrementExact(secretVersion), now);
    }

    @Override
    public String toString() {
        return "EmployeeQr[employeeId=" + employeeId + ", sharedSecret=[REDACTED], qrStatus="
                + qrStatus + ", secretVersion=" + secretVersion + "]";
    }
}
