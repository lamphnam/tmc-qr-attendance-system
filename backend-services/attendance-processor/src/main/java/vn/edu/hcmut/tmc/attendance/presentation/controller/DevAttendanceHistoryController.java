package vn.edu.hcmut.tmc.attendance.presentation.controller;

import java.time.Instant;

import org.springframework.context.annotation.Profile;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.context.request.WebRequest;

import vn.edu.hcmut.tmc.attendance.application.service.AttendanceHistoryService;
import vn.edu.hcmut.tmc.attendance.domain.exception.AttendanceStorageException;
import vn.edu.hcmut.tmc.attendance.presentation.dto.AttendanceHistoryResponseDto;
import vn.edu.hcmut.tmc.attendance.presentation.dto.ErrorResponseDto;

/** Unauthenticated dev inspection only. Client-supplied factoryId is a filter, never authorization. */
@RestController
@Profile("dev")
public class DevAttendanceHistoryController {

    private final AttendanceHistoryService history;

    public DevAttendanceHistoryController(AttendanceHistoryService history) {
        this.history = history;
    }

    @GetMapping("/api/dev/attendance/history")
    public ResponseEntity<AttendanceHistoryResponseDto> query(
            @RequestParam String employeeId,
            @RequestParam String factoryId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant fromInclusive,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant toExclusive,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return devResponse(HttpStatus.OK).body(AttendanceHistoryResponseDto.from(
                history.query(employeeId, factoryId, fromInclusive, toExclusive, page, size)));
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class,
            IllegalArgumentException.class})
    ResponseEntity<ErrorResponseDto> invalidQuery(WebRequest request) {
        return devResponse(HttpStatus.BAD_REQUEST).body(ErrorResponseDto.of("VALIDATION_ERROR",
                "invalid or missing history query parameters", request.getHeader("X-Request-Id")));
    }

    @ExceptionHandler(AttendanceStorageException.class)
    ResponseEntity<ErrorResponseDto> storageDown(WebRequest request) {
        return devResponse(HttpStatus.SERVICE_UNAVAILABLE).body(ErrorResponseDto.of("STORAGE_UNAVAILABLE",
                "central storage unavailable, retry later", request.getHeader("X-Request-Id")));
    }

    private static ResponseEntity.BodyBuilder devResponse(HttpStatus status) {
        return ResponseEntity.status(status).header("X-TMC-Dev-Only", "true").cacheControl(CacheControl.noStore());
    }
}
