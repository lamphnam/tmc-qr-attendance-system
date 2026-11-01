package vn.edu.hcmut.tmc.gate.presentation.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import vn.edu.hcmut.tmc.gate.presentation.dto.ErrorResponseDto;

/**
 * Contract skeleton for the routes listed in docs/API-CONTRACTS.md ([DRAFT] until the owners sign off).
 *
 * <p>STATUS: NOT_IMPLEMENTED (owner: Khoi). Every route answers 501 with the shared error shape
 * instead of a fake success, so other members can wire against the path through the gateway and
 * get an honest answer. Replace a stub with a real use case behind {@code application/} and keep
 * the domain free of framework types (see AGENTS.md).
 */
@RestController
public class GateStationController {

    /** FR-2.1: register / configure a gate */
    @PostMapping("/api/v1/gates")
    public ResponseEntity<ErrorResponseDto> registerGate(
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return notImplemented("POST /api/v1/gates", requestId);
    }

    /** FR-2.2: latest heartbeat / status */
    @GetMapping("/api/v1/gates/{gateId}/status")
    public ResponseEntity<ErrorResponseDto> gateStatus(
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return notImplemented("GET /api/v1/gates/{gateId}/status", requestId);
    }

    /** FR-2.2: guard manual open with mandatory reason */
    @PostMapping("/api/v1/gates/{gateId}/manual-open")
    public ResponseEntity<ErrorResponseDto> manualOpen(
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return notImplemented("POST /api/v1/gates/{gateId}/manual-open", requestId);
    }

    private static ResponseEntity<ErrorResponseDto> notImplemented(String route, String requestId) {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(ErrorResponseDto.of(
                "NOT_IMPLEMENTED", route + " is not implemented yet (owner: Khoi)", requestId));
    }
}
