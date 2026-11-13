package vn.edu.hcmut.tmc.userqr.presentation.dto;

import java.time.Instant;
import vn.edu.hcmut.tmc.userqr.domain.model.EmployeeQr;
import vn.edu.hcmut.tmc.userqr.domain.model.QrStatus;

/** Service-to-kiosk data only, never used by the employee web flow. */
public record WhitelistEmployeeDto(String employeeId, String fullName, String factoryId, String department,
                                    String sharedSecret, QrStatus qrStatus, int secretVersion, Instant updatedAt) {
    public static WhitelistEmployeeDto from(EmployeeQr employee) {
        return new WhitelistEmployeeDto(employee.employeeId(), employee.fullName(), employee.factoryId(), employee.department(),
                employee.sharedSecret(), employee.qrStatus(), employee.secretVersion(), employee.updatedAt());
    }

    @Override
    public String toString() { return "WhitelistEmployeeDto[employeeId=" + employeeId + ", sharedSecret=[REDACTED]]"; }
}
