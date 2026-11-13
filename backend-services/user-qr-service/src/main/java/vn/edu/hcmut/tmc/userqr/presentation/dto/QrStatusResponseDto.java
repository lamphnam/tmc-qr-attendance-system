package vn.edu.hcmut.tmc.userqr.presentation.dto;

import java.time.Instant;
import vn.edu.hcmut.tmc.userqr.domain.model.EmployeeQr;
import vn.edu.hcmut.tmc.userqr.domain.model.QrStatus;

public record QrStatusResponseDto(String employeeId, QrStatus qrStatus, int secretVersion, Instant updatedAt) {
    public static QrStatusResponseDto from(EmployeeQr employee) {
        return new QrStatusResponseDto(employee.employeeId(), employee.qrStatus(), employee.secretVersion(), employee.updatedAt());
    }
}
