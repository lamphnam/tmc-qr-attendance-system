package vn.edu.hcmut.tmc.userqr.presentation.dto;

import java.time.Instant;
import vn.edu.hcmut.tmc.userqr.domain.model.AuditLog;

public record AuditLogResponseDto(long sequence, String logId, Instant timestamp, String actorId,
                                   String actorRole, String actionType, String targetResource,
                                   String payloadSummary, String status, String previousHash, String checksum) {
    public static AuditLogResponseDto from(AuditLog log) {
        var event = log.event();
        return new AuditLogResponseDto(log.sequence(), event.logId(), event.timestamp(), event.actorId(), event.actorRole(),
                event.actionType(), event.targetResource(), event.payloadSummary(), event.status(), log.previousHash(), log.checksum());
    }
}
