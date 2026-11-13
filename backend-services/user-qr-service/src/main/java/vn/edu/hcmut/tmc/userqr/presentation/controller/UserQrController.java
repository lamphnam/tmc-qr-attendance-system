package vn.edu.hcmut.tmc.userqr.presentation.controller;

import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import vn.edu.hcmut.tmc.userqr.application.service.UserQrApplicationService;
import vn.edu.hcmut.tmc.userqr.presentation.dto.*;

/** Synthetic-data demo only; the requested v1 paths are disabled without the dev profile (B21). */
@RestController
@Profile("dev")
@Validated
public class UserQrController {
    private final UserQrApplicationService service;

    public UserQrController(UserQrApplicationService service) { this.service = service; }

    @GetMapping("/api/v1/qr/{employeeId}")
    public ResponseEntity<QrResponseDto> qr(
            @PathVariable @Pattern(regexp = "[A-Za-z0-9_-]{1,36}") String employeeId) {
        return demo(QrResponseDto.from(service.issueQr(employeeId)));
    }

    @PostMapping("/api/v1/qr/{employeeId}/rotate")
    public ResponseEntity<QrResponseDto> rotate(
            @PathVariable @Pattern(regexp = "[A-Za-z0-9_-]{1,36}") String employeeId,
            @Valid @RequestBody(required = false) QrActionRequestDto body) {
        QrActionRequestDto action = body == null ? QrActionRequestDto.empty() : body;
        return demo(QrResponseDto.from(service.rotateSecret(employeeId, action.actorId(), action.actorRole(), action.reason())));
    }

    @PostMapping("/api/v1/qr/{employeeId}/revoke")
    public ResponseEntity<QrStatusResponseDto> revoke(
            @PathVariable @Pattern(regexp = "[A-Za-z0-9_-]{1,36}") String employeeId,
            @Valid @RequestBody(required = false) QrActionRequestDto body) {
        QrActionRequestDto action = body == null ? QrActionRequestDto.empty() : body;
        return demo(QrStatusResponseDto.from(service.revokeQr(employeeId, action.actorId(), action.actorRole(), action.reason())));
    }

    @PostMapping("/api/v1/qr/{employeeId}/activate")
    public ResponseEntity<QrResponseDto> activate(
            @PathVariable @Pattern(regexp = "[A-Za-z0-9_-]{1,36}") String employeeId,
            @Valid @RequestBody(required = false) QrActionRequestDto body) {
        QrActionRequestDto action = body == null ? QrActionRequestDto.empty() : body;
        return demo(QrResponseDto.from(service.activateQr(employeeId, action.actorId(), action.actorRole(), action.reason())));
    }

    @GetMapping("/api/v1/qr/audit-logs")
    public ResponseEntity<List<AuditLogResponseDto>> auditLogs(
            @RequestParam(defaultValue = "100") @Min(1) @Max(200) int limit) {
        return demo(service.auditLogs(limit).stream().map(AuditLogResponseDto::from).toList());
    }

    static <T> ResponseEntity<T> demo(T body) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").header("Pragma", "no-cache")
                .header("X-TMC-Dev-Only", "true").body(body);
    }
}
