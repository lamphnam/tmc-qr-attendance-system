package vn.edu.hcmut.tmc.kiosk.domain.model;

import java.util.UUID;

public record EventSyncResult(UUID attendanceId, SyncOutcome outcome, String reason) {
}
