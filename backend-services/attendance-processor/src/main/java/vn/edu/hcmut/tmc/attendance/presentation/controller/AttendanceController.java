package vn.edu.hcmut.tmc.attendance.presentation.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import vn.edu.hcmut.tmc.attendance.presentation.dto.ErrorResponseDto;

/**
 * Production ingestion routes from M1 / docs/API-CONTRACTS.md sections 4-5.
 *
 * <p>STATUS: NOT_IMPLEMENTED (owner: Khoi). These deliberately answer 501 instead of pretending to
 * work: there is no kiosk-to-central authentication yet (D04) and the wire contract is still DRAFT (D01).
 * The working ingestion logic is {@code AttendanceIngestionService}; the dev-only fixture in
 * {@link DevAttendanceController} shows how to call it. Khoi: move that logic behind auth here.
 */
@RestController
@RequestMapping("/api/v1/attendance")
public class AttendanceController {

    @PostMapping({"/events", "/sync"})
    public ResponseEntity<ErrorResponseDto> notImplemented(
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(ErrorResponseDto.of(
                "NOT_IMPLEMENTED",
                "Authenticated attendance ingestion is not implemented yet (owner: Khoi, decisions D01/D04). "
                        + "In the dev profile use POST /api/dev/attendance/events or /sync.",
                requestId));
    }
}
