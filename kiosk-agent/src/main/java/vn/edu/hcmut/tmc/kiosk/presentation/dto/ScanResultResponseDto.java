package vn.edu.hcmut.tmc.kiosk.presentation.dto;

import java.util.UUID;

import vn.edu.hcmut.tmc.kiosk.application.usecase.ProcessQrScanUseCase.Outcome;
import vn.edu.hcmut.tmc.kiosk.domain.model.RejectedScan.Reason;
import vn.edu.hcmut.tmc.kiosk.domain.model.GateCommandStatus;
import vn.edu.hcmut.tmc.kiosk.domain.model.ScanResult;

public record ScanResultResponseDto(ScanResult result, UUID attendanceId, GateCommandStatus gateCommand,
                                    long processingTimeMs, Reason reason) {
    public static ScanResultResponseDto from(Outcome outcome) {
        return new ScanResultResponseDto(outcome.result(), outcome.attendanceId(), outcome.gateCommand(),
                outcome.processingTimeMs(), outcome.reason());
    }
}
