package vn.edu.hcmut.tmc.kiosk.domain.model;

import java.util.Arrays;
import java.util.Objects;

/**
 * Local verification data, never an HTTP response DTO. Secret encoding and protection are not defined here (D08).
 * Missing material is allowed independently of QR status until the owners agree that policy; ACTIVE alone is
 * therefore not proof that an employee can be verified. No secret length, TTL or rotation policy is imposed.
 *
 * @param secretMaterial nullable when verification material is unavailable; non-empty otherwise
 */
public record WhitelistEmployee(String employeeId, QrStatus qrStatus, int secretVersion, byte[] secretMaterial) {

    public WhitelistEmployee {
        if (employeeId == null || employeeId.isBlank()) {
            throw new IllegalArgumentException("employeeId must not be blank");
        }
        employeeId = employeeId.strip();
        if (qrStatus == null) {
            throw new IllegalArgumentException("qrStatus must not be null");
        }
        if (secretVersion < 1) {
            throw new IllegalArgumentException("secretVersion must be positive");
        }
        if (secretMaterial != null && secretMaterial.length == 0) {
            throw new IllegalArgumentException("secretMaterial must not be empty when present");
        }
        secretMaterial = secretMaterial == null ? null : secretMaterial.clone();
    }

    @Override
    public byte[] secretMaterial() {
        return secretMaterial == null ? null : secretMaterial.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof WhitelistEmployee employee
                && employeeId.equals(employee.employeeId)
                && qrStatus == employee.qrStatus
                && secretVersion == employee.secretVersion
                && Arrays.equals(secretMaterial, employee.secretMaterial);
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hash(employeeId, qrStatus, secretVersion) + Arrays.hashCode(secretMaterial);
    }

    @Override
    public String toString() {
        return "WhitelistEmployee[employeeId=" + employeeId + ", qrStatus=" + qrStatus
                + ", secretVersion=" + secretVersion + ", secretMaterial=<redacted>]";
    }
}
