package vn.edu.hcmut.tmc.userqr.presentation.controller;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vn.edu.hcmut.tmc.userqr.presentation.dto.ErrorResponseDto;

@RestController
@Profile("!dev")
public class UnimplementedQrController {
    @RequestMapping({"/api/v1/qr/{employeeId}", "/api/v1/qr/{employeeId}/rotate",
            "/api/v1/qr/{employeeId}/revoke", "/api/v1/qr/{employeeId}/activate", "/api/v1/kiosk/whitelist"})
    public ResponseEntity<ErrorResponseDto> unavailable(
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(ErrorResponseDto.of(
                "NOT_IMPLEMENTED", "Production QR endpoints require authentication and owner contract approval", requestId));
    }
}
