package vn.edu.hcmut.tmc.userqr.presentation.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import vn.edu.hcmut.tmc.userqr.presentation.dto.ErrorResponseDto;

/**
 * Contract skeleton for the routes listed in docs/API-CONTRACTS.md ([DRAFT] until the owners sign off).
 *
 * <p>STATUS: NOT_IMPLEMENTED (owner: Khoa). Every route answers 501 with the shared error shape
 * instead of a fake success, so other members can wire against the path through the gateway and
 * get an honest answer. Replace a stub with a real use case behind {@code application/} and keep
 * the domain free of framework types (see AGENTS.md).
 */
@RestController
public class UserQrController {

    /** FR-1.3, ADR-02: dynamic QR payload (never the shared secret) */
    @GetMapping("/api/v1/qr/generate")
    public ResponseEntity<ErrorResponseDto> generateQr(
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return notImplemented("GET /api/v1/qr/generate", requestId);
    }

    /** FR-1.2, ADR-02: kiosk-authenticated whitelist snapshot */
    @GetMapping("/api/v1/kiosk/whitelist")
    public ResponseEntity<ErrorResponseDto> kioskWhitelist(
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return notImplemented("GET /api/v1/kiosk/whitelist", requestId);
    }

    private static ResponseEntity<ErrorResponseDto> notImplemented(String route, String requestId) {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(ErrorResponseDto.of(
                "NOT_IMPLEMENTED", route + " is not implemented yet (owner: Khoa)", requestId));
    }
}
