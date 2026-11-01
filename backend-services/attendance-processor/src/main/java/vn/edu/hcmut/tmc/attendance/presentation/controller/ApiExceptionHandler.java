package vn.edu.hcmut.tmc.attendance.presentation.controller;

import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

import vn.edu.hcmut.tmc.attendance.domain.exception.AttendanceStorageException;
import vn.edu.hcmut.tmc.attendance.presentation.dto.ErrorResponseDto;

/** Maps failures to the shared error shape. Never echoes request bodies or stack traces. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponseDto> validation(MethodArgumentNotValidException e, WebRequest request) {
        String fields = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField())
                .distinct()
                .collect(Collectors.joining(", "));
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "invalid or missing fields: " + fields, request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponseDto> unreadable(WebRequest request) {
        return error(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "request body is not valid JSON", request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ErrorResponseDto> invalidEvent(IllegalArgumentException e, WebRequest request) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_EVENT", e.getMessage(), request);
    }

    @ExceptionHandler(AttendanceStorageException.class)
    ResponseEntity<ErrorResponseDto> storageDown(WebRequest request) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE", "central storage unavailable, retry later", request);
    }

    private static ResponseEntity<ErrorResponseDto> error(HttpStatus status, String code, String message, WebRequest request) {
        return ResponseEntity.status(status).body(ErrorResponseDto.of(code, message, request.getHeader("X-Request-Id")));
    }
}
