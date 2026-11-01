package vn.edu.hcmut.tmc.kiosk.presentation.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import vn.edu.hcmut.tmc.kiosk.presentation.dto.ErrorResponseDto;
import vn.edu.hcmut.tmc.kiosk.presentation.dto.QrScanRequestDto;

/**
 * {@code POST /api/local/scan} (docs/API-CONTRACTS.md section 3) - the real FR-3.1 entry point.
 *
 * <p>STATUS: NOT_IMPLEMENTED (owner: Khanh). There is no TOTP validator, whitelist cache or anti-replay yet, so this
 * answers 501 rather than ever returning ALLOWED. When implemented, the controller must stay transport-only and
 * delegate to a {@code ProcessQrScanUseCase} that commits the event (and the used-token row) to SQLite BEFORE the
 * open-gate command, mirroring what {@code QueueSyntheticEventUseCase} already demonstrates for the queue part.
 */
@RestController
@RequestMapping("/api/local")
public class KioskScanController {

    @PostMapping("/scan")
    public ResponseEntity<ErrorResponseDto> scan(@RequestBody(required = false) QrScanRequestDto ignored,
                                                 @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(ErrorResponseDto.of(
                "NOT_IMPLEMENTED",
                "QR scan (TOTP + whitelist + anti-replay) is not implemented yet (owner: Khanh). "
                        + "No gate command was sent and no event was recorded.",
                requestId));
    }
}
