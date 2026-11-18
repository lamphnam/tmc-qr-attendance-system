package vn.edu.hcmut.tmc.attendance.presentation.controller;

import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import vn.edu.hcmut.tmc.attendance.application.service.AttendanceEventsService;
import vn.edu.hcmut.tmc.attendance.domain.exception.AttendanceStorageException;
import vn.edu.hcmut.tmc.attendance.presentation.dto.AttendanceEventsResponseDto;
import vn.edu.hcmut.tmc.attendance.presentation.dto.ErrorResponseDto;

/** Explicit v1-path exception for the M2 demo, unauthenticated and absent outside dev. */
@RestController
@Profile("dev")
public class DevAttendanceEventsController {

    private final AttendanceEventsService events;

    public DevAttendanceEventsController(AttendanceEventsService events) {
        this.events = events;
    }

    @GetMapping("/api/v1/attendance/events")
    public ResponseEntity<AttendanceEventsResponseDto> recent(@RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String employeeId) {
        return response(HttpStatus.OK).body(AttendanceEventsResponseDto.from(events.recent(limit, employeeId)));
    }

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ErrorResponseDto> invalid(WebRequest request) {
        return response(HttpStatus.BAD_REQUEST).body(ErrorResponseDto.of("VALIDATION_ERROR",
                "invalid attendance event query parameters", request.getHeader("X-Request-Id")));
    }

    @ExceptionHandler(AttendanceStorageException.class)
    ResponseEntity<ErrorResponseDto> storageDown(WebRequest request) {
        return response(HttpStatus.SERVICE_UNAVAILABLE).body(ErrorResponseDto.of("STORAGE_UNAVAILABLE",
                "central storage unavailable, retry later", request.getHeader("X-Request-Id")));
    }

    private static ResponseEntity.BodyBuilder response(HttpStatus status) {
        return ResponseEntity.status(status).header("X-TMC-Dev-Only", "true").cacheControl(CacheControl.noStore());
    }
}
