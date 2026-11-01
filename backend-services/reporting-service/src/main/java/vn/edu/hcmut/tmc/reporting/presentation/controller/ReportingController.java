package vn.edu.hcmut.tmc.reporting.presentation.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import vn.edu.hcmut.tmc.reporting.presentation.dto.ErrorResponseDto;

/**
 * Contract skeleton for the routes listed in docs/API-CONTRACTS.md ([DRAFT] until the owners sign off).
 *
 * <p>STATUS: NOT_IMPLEMENTED (owner: Nam). Every route answers 501 with the shared error shape
 * instead of a fake success, so other members can wire against the path through the gateway and
 * get an honest answer. Replace a stub with a real use case behind {@code application/} and keep
 * the domain free of framework types (see AGENTS.md).
 */
@RestController
public class ReportingController {

    /** FR-4.1 / NFR-7: report by factory/date/shift */
    @GetMapping("/api/v1/reports/attendance")
    public ResponseEntity<ErrorResponseDto> attendanceReport(
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return notImplemented("GET /api/v1/reports/attendance", requestId);
    }

    /** FR-4.2: factory dashboard */
    @GetMapping("/api/v1/reports/dashboard")
    public ResponseEntity<ErrorResponseDto> dashboard(
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return notImplemented("GET /api/v1/reports/dashboard", requestId);
    }

    /** ADR-03 / NFR-3,6: manual trigger of the monthly export */
    @PostMapping("/api/v1/reports/hr-export/trigger")
    public ResponseEntity<ErrorResponseDto> triggerHrExport(
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return notImplemented("POST /api/v1/reports/hr-export/trigger", requestId);
    }

    private static ResponseEntity<ErrorResponseDto> notImplemented(String route, String requestId) {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(ErrorResponseDto.of(
                "NOT_IMPLEMENTED", route + " is not implemented yet (owner: Nam)", requestId));
    }
}
