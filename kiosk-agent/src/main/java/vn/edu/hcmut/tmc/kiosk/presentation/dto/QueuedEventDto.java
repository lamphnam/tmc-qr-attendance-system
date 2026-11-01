package vn.edu.hcmut.tmc.kiosk.presentation.dto;

import vn.edu.hcmut.tmc.kiosk.domain.model.LocalAttendanceEvent;

/**
 * View of one queue row. {@code syncStatus} is the CENTRAL sync state; {@code gateCommandStatus} is whether the open
 * command was accepted. Neither means a physical gate opened.
 */
public record QueuedEventDto(
        String attendanceId,
        String employeeId,
        String factoryId,
        String gateId,
        String direction,
        String occurredAt,
        String scanResult,
        String syncStatus,
        String gateCommandStatus,
        String gateCommandDetail,
        int retryCount,
        String nextRetryAt,
        String lastError,
        boolean devFixture) {

    public static QueuedEventDto from(LocalAttendanceEvent e) {
        return new QueuedEventDto(
                e.attendanceId().toString(), e.employeeId(), e.factoryId(), e.gateId(), e.direction().name(),
                e.occurredAt().toString(), e.scanResult().name(), e.syncStatus().name(), e.gateCommandStatus().name(),
                e.gateCommandDetail(), e.retryCount(), e.nextRetryAt() == null ? null : e.nextRetryAt().toString(),
                e.lastError(), e.devFixture());
    }
}
