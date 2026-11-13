package vn.edu.hcmut.tmc.userqr.domain.model;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

public record AuditEvent(String logId, Instant timestamp, String actorId, String actorRole,
                         String actionType, String targetResource, String payloadSummary, String status) {
    public AuditEvent {
        timestamp = timestamp.truncatedTo(ChronoUnit.MILLIS);
    }
}
