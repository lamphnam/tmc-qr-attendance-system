package vn.edu.hcmut.tmc.userqr.presentation.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vn.edu.hcmut.tmc.userqr.presentation.dto.ErrorResponseDto;

/** The M1 route cannot infer an employee identity until JWT authentication exists. */
@RestController
public class QrGenerateContractController {
    @GetMapping("/api/v1/qr/generate")
    public ResponseEntity<ErrorResponseDto> generate(
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(ErrorResponseDto.of(
                "NOT_IMPLEMENTED", "Authenticated QR generation is not implemented", requestId));
    }
}
