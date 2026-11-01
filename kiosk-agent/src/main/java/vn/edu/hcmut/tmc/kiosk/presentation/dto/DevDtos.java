package vn.edu.hcmut.tmc.kiosk.presentation.dto;

import java.util.List;

import vn.edu.hcmut.tmc.kiosk.domain.model.QueueCounts;

/** Request / response shapes of the dev-only fixture endpoints. */
public final class DevDtos {

    private DevDtos() {
    }

    /** Every field optional; omitted fields fall back to defaults (EMP001, the default gate, IN). */
    public record SyntheticEventRequest(String employeeId, String gateId, String direction) {
    }

    public record QueueSnapshot(QueueCounts counts, List<QueuedEventDto> events) {
    }

    public record OfflineRequest(Boolean disabled) {
    }

    public record SyncState(boolean centralSyncDisabled) {
    }
}
