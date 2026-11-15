package vn.edu.hcmut.tmc.reporting.presentation.dto;

import java.time.Instant;

import vn.edu.hcmut.tmc.reporting.domain.model.HrExportBatch;

/** Wire view of one batch. {@code lastError} is a short diagnostic ("HTTP 503"), never a payload. */
public record HrExportBatchDto(String batchUuid, String period, String status, String triggerSource, Instant createdAt,
                               Instant retryDeadline, int attemptCount, Instant lastAttemptAt, Instant nextRetryAt,
                               String lastError, int itemCount) {

    public static HrExportBatchDto from(HrExportBatch b) {
        return b == null ? null : new HrExportBatchDto(b.batchUuid().toString(), b.period().label(), b.status().name(),
                b.triggerSource().name(), b.createdAt(), b.retryDeadline(), b.attemptCount(), b.lastAttemptAt(),
                b.nextRetryAt(), b.lastError(), b.itemCount());
    }
}
