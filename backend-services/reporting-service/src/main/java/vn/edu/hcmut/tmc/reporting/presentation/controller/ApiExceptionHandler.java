package vn.edu.hcmut.tmc.reporting.presentation.controller;

import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import vn.edu.hcmut.tmc.reporting.domain.exception.ReportingStorageException;
import vn.edu.hcmut.tmc.reporting.presentation.dto.ErrorResponseDto;

/** Maps failures to {@code {code, message, correlationId}}. Never echoes request values, bodies or stack traces. */
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

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ErrorResponseDto> missingParameter(MissingServletRequestParameterException e, WebRequest request) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "missing parameter: " + e.getParameterName(), request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ErrorResponseDto> badParameter(MethodArgumentTypeMismatchException e, WebRequest request) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "invalid value for parameter: " + e.getName(), request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponseDto> unreadable(WebRequest request) {
        return error(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "request body is not valid JSON", request);
    }

    /** Domain rules (range too long, period not over yet, bad factory id) speak in fixed messages, never in input. */
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ErrorResponseDto> invalid(IllegalArgumentException e, WebRequest request) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", e.getMessage(), request);
    }

    @ExceptionHandler(ReportingStorageException.class)
    ResponseEntity<ErrorResponseDto> storageDown(WebRequest request) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE", "central storage unavailable, retry later", request);
    }

    private static ResponseEntity<ErrorResponseDto> error(HttpStatus status, String code, String message, WebRequest request) {
        return ResponseEntity.status(status).body(ErrorResponseDto.of(code, message, request.getHeader("X-Request-Id")));
    }
}
