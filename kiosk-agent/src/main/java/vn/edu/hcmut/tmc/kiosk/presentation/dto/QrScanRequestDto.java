package vn.edu.hcmut.tmc.kiosk.presentation.dto;

/** Draft input of POST /api/local/scan. The kiosk clock is authoritative. */
public record QrScanRequestDto(String gateId, String direction, QrPayload qrPayload) {

    public record QrPayload(String empId, String totp, Long ts, Integer ver) {
        @Override
        public String toString() {
            return "QrPayload[empId=" + empId + ", totp=<redacted>, ts=" + ts + ", ver=" + ver + "]";
        }
    }
}
