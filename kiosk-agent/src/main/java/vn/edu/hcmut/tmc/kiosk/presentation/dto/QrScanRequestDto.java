package vn.edu.hcmut.tmc.kiosk.presentation.dto;

/**
 * [DRAFT] docs/API-CONTRACTS.md section 3 request of {@code POST /api/local/scan}. Only documents the contract for now:
 * the endpoint that consumes it answers 501.
 */
public record QrScanRequestDto(String gateId, String direction, QrPayload qrPayload) {

    /** The TOTP is a credential: {@code toString} redacts it so it can never leak through logging. */
    public record QrPayload(String empId, String totp, long ts, int ver) {
        @Override
        public String toString() {
            return "QrPayload[empId=" + empId + ", totp=<redacted>, ts=" + ts + ", ver=" + ver + "]";
        }
    }
}
