package vn.edu.hcmut.tmc.reporting.presentation.controller;

import java.time.LocalDate;

import jakarta.validation.Valid;

import org.springframework.context.annotation.Profile;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import vn.edu.hcmut.tmc.reporting.application.service.HrExportResult;
import vn.edu.hcmut.tmc.reporting.application.service.HrExportService;
import vn.edu.hcmut.tmc.reporting.application.service.ReportQueryService;
import vn.edu.hcmut.tmc.reporting.domain.model.ExportPeriod;
import vn.edu.hcmut.tmc.reporting.presentation.dto.AttendanceReportDto;
import vn.edu.hcmut.tmc.reporting.presentation.dto.DashboardDto;
import vn.edu.hcmut.tmc.reporting.presentation.dto.ErrorResponseDto;
import vn.edu.hcmut.tmc.reporting.presentation.dto.HrExportBatchDto;
import vn.edu.hcmut.tmc.reporting.presentation.dto.HrExportTriggerRequestDto;
import vn.edu.hcmut.tmc.reporting.presentation.dto.HrExportTriggerResponseDto;

/**
 * DEV-ONLY routes for the real reporting logic. Exists only under the {@code dev} profile and is
 * <strong>unauthenticated</strong>: the production {@code /api/v1/reports/*} routes stay 501 until JWT/RBAC exists
 * (HR/Admin for the export, D04). Transport only: parameters in, DTO out, status codes.
 */
@RestController
@Profile("dev")
@RequestMapping("/api/dev/reports")
public class DevReportingController {

    static final String DEV_HEADER = "X-TMC-Dev-Only";

    private final ReportQueryService reports;
    private final HrExportService hrExport;

    public DevReportingController(ReportQueryService reports, HrExportService hrExport) {
        this.reports = reports;
        this.hrExport = hrExport;
    }

    @GetMapping("/attendance")
    public ResponseEntity<AttendanceReportDto> attendance(
            @RequestParam String factoryId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String department,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "100") int limit) {
        return devOk(AttendanceReportDto.from(reports.attendanceReport(factoryId, from, to, department, offset, limit)));
    }

    @GetMapping("/dashboard")
    public ResponseEntity<DashboardDto> dashboard(
            @RequestParam String factoryId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return devOk(DashboardDto.from(reports.dashboard(factoryId, date)));
    }

    @PostMapping("/hr-export/trigger")
    public ResponseEntity<?> triggerHrExport(@Valid @RequestBody HrExportTriggerRequestDto request,
                                             @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        HrExportResult result = hrExport.triggerManually(ExportPeriod.parse(request.period()));
        String period = request.period();
        return switch (result.outcome()) {
            case DELIVERED -> devOk(HrExportTriggerResponseDto.from(result));
            case RETRY_SCHEDULED -> ResponseEntity.status(HttpStatus.ACCEPTED).header(DEV_HEADER, "true")
                    .body(HrExportTriggerResponseDto.from(result));
            case FAILED -> devError(HttpStatus.BAD_GATEWAY, "HR_EXPORT_FAILED",
                    "batch for " + period + " is FAILED and an alert was raised; see GET /api/dev/reports/hr-export/batches/" + period,
                    requestId);
            case ALREADY_EXPORTED -> devError(HttpStatus.CONFLICT, "ALREADY_EXPORTED",
                    "period " + period + " was already delivered; it is never sent twice", requestId);
            case IN_PROGRESS -> devError(HttpStatus.CONFLICT, "EXPORT_IN_PROGRESS",
                    "another attempt for " + period + " is running; retry later", requestId);
        };
    }

    @GetMapping("/hr-export/batches/{period}")
    public ResponseEntity<?> batch(@PathVariable String period,
                                   @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return hrExport.find(ExportPeriod.parse(period))
                .<ResponseEntity<?>>map(batch -> devOk(HrExportBatchDto.from(batch)))
                .orElseGet(() -> devError(HttpStatus.NOT_FOUND, "NOT_FOUND", "no export batch for this period", requestId));
    }

    private static <T> ResponseEntity<T> devOk(T body) {
        return ResponseEntity.ok().header(DEV_HEADER, "true").body(body);
    }

    private static ResponseEntity<ErrorResponseDto> devError(HttpStatus status, String code, String message, String requestId) {
        return ResponseEntity.status(status).header(DEV_HEADER, "true").body(ErrorResponseDto.of(code, message, requestId));
    }
}
