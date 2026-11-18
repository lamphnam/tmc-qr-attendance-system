package vn.edu.hcmut.tmc.kiosk.presentation.controller;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import vn.edu.hcmut.tmc.kiosk.application.usecase.ProcessQrScanUseCase;
import vn.edu.hcmut.tmc.kiosk.domain.model.Direction;
import vn.edu.hcmut.tmc.kiosk.presentation.dto.QrScanRequestDto;
import vn.edu.hcmut.tmc.kiosk.presentation.dto.QrScanRequestDto.QrPayload;
import vn.edu.hcmut.tmc.kiosk.presentation.dto.ScanResultResponseDto;

@Profile("dev")
@RestController
public class KioskScanController {
    private final ProcessQrScanUseCase scan;

    public KioskScanController(ProcessQrScanUseCase scan) {
        this.scan = scan;
    }

    @PostMapping("/api/local/scan")
    public ResponseEntity<ScanResultResponseDto> scan(@RequestBody QrScanRequestDto request) {
        QrPayload qr = request.qrPayload();
        if (!validId(request.gateId()) || qr == null || !validId(qr.empId())
                || qr.totp() == null || !qr.totp().matches("[0-9]{6}")
                || qr.ts() == null || qr.ts() < 0 || qr.ver() == null || qr.ver() < 1) {
            throw new IllegalArgumentException("gateId and a valid qrPayload are required");
        }
        Direction direction = switch (request.direction() == null ? "" : request.direction()) {
            case "IN" -> Direction.IN;
            case "OUT" -> Direction.OUT;
            default -> throw new IllegalArgumentException("direction must be IN or OUT");
        };
        var outcome = scan.execute(request.gateId(), direction, qr.empId(), qr.totp(), qr.ver());
        return ResponseEntity.ok().header("X-TMC-Dev-Only", "true").body(ScanResultResponseDto.from(outcome));
    }

    private static boolean validId(String id) {
        return id != null && !id.isBlank() && id.length() <= 32
                && id.equals(id.strip()) && id.codePoints().noneMatch(Character::isISOControl);
    }
}
