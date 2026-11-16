package vn.edu.hcmut.tmc.userqr.presentation.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import vn.edu.hcmut.tmc.userqr.domain.exception.*;
import vn.edu.hcmut.tmc.userqr.presentation.dto.ErrorResponseDto;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(EmployeeNotFoundException.class)
    ResponseEntity<ErrorResponseDto> missing(HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "NOT_FOUND", "Employee was not found", request);
    }

    @ExceptionHandler(FactoryNotFoundException.class)
    ResponseEntity<ErrorResponseDto> missingFactory(HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "NOT_FOUND", "Factory was not found", request);
    }

    @ExceptionHandler(QrRevokedException.class)
    ResponseEntity<ErrorResponseDto> revoked(HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "QR_REVOKED", "Employee QR has been revoked", request);
    }

    @ExceptionHandler({QrStorageException.class, TransactionException.class})
    ResponseEntity<ErrorResponseDto> storage(HttpServletRequest request) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE", "QR storage is unavailable", request);
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            ConstraintViolationException.class, HandlerMethodValidationException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class})
    ResponseEntity<ErrorResponseDto> validation(HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Request validation failed", request);
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ErrorResponseDto> kiosk(ResponseStatusException exception, HttpServletRequest request) {
        boolean forbidden = exception.getStatusCode().value() == 403;
        return error(forbidden ? HttpStatus.FORBIDDEN : HttpStatus.SERVICE_UNAVAILABLE,
                forbidden ? "FORBIDDEN" : "KIOSK_AUTH_NOT_CONFIGURED",
                forbidden ? "Kiosk credential is required" : "Kiosk credential is not configured", request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ErrorResponseDto> method(HttpServletRequest request) {
        return error(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "HTTP method is not supported", request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ErrorResponseDto> mediaType(HttpServletRequest request) {
        return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", "Use a JSON request body", request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ErrorResponseDto> route(HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource was not found", request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponseDto> unexpected(HttpServletRequest request) {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Request could not be completed", request);
    }

    private ResponseEntity<ErrorResponseDto> error(HttpStatus status, String code, String message, HttpServletRequest request) {
        return ResponseEntity.status(status).header("Cache-Control", "no-store").body(
                ErrorResponseDto.of(code, message, request.getHeader("X-Request-Id")));
    }
}
