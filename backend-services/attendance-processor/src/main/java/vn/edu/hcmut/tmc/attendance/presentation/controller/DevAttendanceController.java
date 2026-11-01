package vn.edu.hcmut.tmc.attendance.presentation.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import vn.edu.hcmut.tmc.attendance.application.service.AttendanceIngestionService;
import vn.edu.hcmut.tmc.attendance.application.service.IngestionResult;
import vn.edu.hcmut.tmc.attendance.presentation.dto.AttendanceEventDto;
import vn.edu.hcmut.tmc.attendance.presentation.dto.BatchSyncRequestDto;
import vn.edu.hcmut.tmc.attendance.presentation.dto.BatchSyncResponseDto;
import vn.edu.hcmut.tmc.attendance.presentation.dto.ErrorResponseDto;
import vn.edu.hcmut.tmc.attendance.presentation.mapper.AttendanceSyncResponseMapper;

/**
 * DEV-ONLY vertical-slice fixture (P1). Only exists under the {@code dev} Spring profile and is
 * <strong>unauthenticated</strong> - never enable it outside a developer machine.
 *
 * <p>Rows written here are flagged {@code dev_fixture = true}. Deduplication is by {@code attendanceId};
 * an {@code Idempotency-Key} header is accepted but ignored in this slice.
 */
@RestController
@Profile("dev")
@RequestMapping("/api/dev/attendance")
public class DevAttendanceController {

    static final String DEV_HEADER = "X-TMC-Dev-Only";

    private final AttendanceIngestionService ingestionService;
    private final AttendanceSyncResponseMapper mapper;

    public DevAttendanceController(AttendanceIngestionService ingestionService,
                                   AttendanceSyncResponseMapper mapper) {
        this.ingestionService = ingestionService;
        this.mapper = mapper;
    }

    @PostMapping("/events")
    public ResponseEntity<?> ingestOne(@Valid @RequestBody AttendanceEventDto dto,
                                       @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        IngestionResult result = ingestionService.ingest(dto.toDomain(null, true));
        return switch (result.status()) {
            case STORED, ALREADY_EXISTS -> devOk(mapper.toAck(result));
            case CONFLICT -> devError(HttpStatus.CONFLICT, "ATTENDANCE_ID_CONFLICT", result.reason(), requestId);
            case REJECTED -> devError(HttpStatus.UNPROCESSABLE_ENTITY, "EVENT_REJECTED", result.reason(), requestId);
            case RETRYABLE -> devError(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE", result.reason(), requestId);
        };
    }

    @PostMapping("/sync")
    public ResponseEntity<BatchSyncResponseDto> ingestBatch(@Valid @RequestBody BatchSyncRequestDto request) {
        List<IngestionResult> results = new ArrayList<>(request.events().size());
        for (AttendanceEventDto event : request.events()) {
            try {
                results.add(ingestionService.ingest(event.toDomain(request.kioskId(), true)));
            } catch (IllegalArgumentException invalid) {
                results.add(IngestionResult.rejected(String.valueOf(event.attendanceId()), invalid.getMessage()));
            }
        }
        return devOk(mapper.toBatchResponse(results));
    }

    @GetMapping("/events/{attendanceId}")
    public ResponseEntity<?> findOne(@PathVariable UUID attendanceId,
                                     @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return ingestionService.find(attendanceId)
                .<ResponseEntity<?>>map(record -> devOk(AttendanceEventDto.from(record)))
                .orElseGet(() -> devError(HttpStatus.NOT_FOUND, "NOT_FOUND", "no such attendanceId", requestId));
    }

    private <T> ResponseEntity<T> devOk(T body) {
        return ResponseEntity.ok().header(DEV_HEADER, "true").body(body);
    }

    private ResponseEntity<ErrorResponseDto> devError(HttpStatus status, String code, String message, String requestId) {
        return ResponseEntity.status(status).header(DEV_HEADER, "true")
                .body(ErrorResponseDto.of(code, message, requestId));
    }
}
