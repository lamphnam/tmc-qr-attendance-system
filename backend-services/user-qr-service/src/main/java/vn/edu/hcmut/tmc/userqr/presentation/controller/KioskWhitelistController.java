package vn.edu.hcmut.tmc.userqr.presentation.controller;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import jakarta.validation.constraints.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.hcmut.tmc.userqr.application.service.UserQrApplicationService;
import vn.edu.hcmut.tmc.userqr.presentation.dto.WhitelistEmployeeDto;

@RestController
@Profile("dev")
@Validated
public class KioskWhitelistController {
    private final UserQrApplicationService service;
    private final String kioskApiKey;

    public KioskWhitelistController(UserQrApplicationService service,
            @Value("${tmc.qr.kiosk-api-key:}") String kioskApiKey) {
        this.service = service;
        this.kioskApiKey = kioskApiKey;
    }

    @GetMapping("/api/v1/kiosk/whitelist")
    public ResponseEntity<List<WhitelistEmployeeDto>> whitelist(
            @RequestHeader(value = "X-Kiosk-Api-Key", required = false) String credential,
            @RequestParam @Pattern(regexp = "[A-Za-z0-9_-]{1,16}") String factoryId) {
        if (kioskApiKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Kiosk credential is not configured");
        }
        if (credential == null || !MessageDigest.isEqual(kioskApiKey.getBytes(StandardCharsets.UTF_8),
                credential.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Kiosk credential is required");
        }
        var snapshot = service.exportWhitelist(factoryId);
        return ResponseEntity.ok().header("Cache-Control", "no-store").header("Pragma", "no-cache")
                .header("X-TMC-Dev-Only", "true").header("X-Whitelist-Version", Long.toString(snapshot.version()))
                .body(snapshot.employees().stream().map(WhitelistEmployeeDto::from).toList());
    }
}
