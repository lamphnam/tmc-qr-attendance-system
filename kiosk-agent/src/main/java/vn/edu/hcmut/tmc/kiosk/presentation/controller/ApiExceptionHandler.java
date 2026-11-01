package vn.edu.hcmut.tmc.kiosk.presentation.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

import vn.edu.hcmut.tmc.kiosk.presentation.dto.ErrorResponseDto;

/** Maps failures to the shared error shape. Never echoes request bodies (they may carry QR / TOTP data). */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponseDto> unreadable(WebRequest request) {
        return error(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "request body is not valid JSON", request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ErrorResponseDto> invalid(IllegalArgumentException e, WebRequest request) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", e.getMessage(), request);
    }

    private static ResponseEntity<ErrorResponseDto> error(HttpStatus status, String code, String message, WebRequest request) {
        return ResponseEntity.status(status).body(ErrorResponseDto.of(code, message, request.getHeader("X-Request-Id")));
    }
}
