package vn.edu.hcmut.tmc.kiosk.presentation.controller;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import vn.edu.hcmut.tmc.kiosk.presentation.dto.ErrorResponseDto;

@Profile("!dev")
@RestController
public class UnimplementedScanController {
    @PostMapping("/api/local/scan")
    public ResponseEntity<ErrorResponseDto> scan(@RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(ErrorResponseDto.of(
                "NOT_IMPLEMENTED", "production kiosk scan is not available", requestId));
    }
}
