package vn.edu.hcmut.tmc.reporting.application.service;

import vn.edu.hcmut.tmc.reporting.domain.model.DeliveryResult;
import vn.edu.hcmut.tmc.reporting.domain.model.HrExportBatch;

/** What one export request or attempt did. {@code delivery} is null when no send happened. */
public record HrExportResult(Outcome outcome, HrExportBatch batch, DeliveryResult delivery) {

    public enum Outcome {
        /** This attempt delivered the batch (ACKNOWLEDGED or SENT). */
        DELIVERED,
        /** The attempt failed transiently; the batch is PENDING with a next retry before its deadline. */
        RETRY_SCHEDULED,
        /** The batch is FAILED and an alert was raised. */
        FAILED,
        /** The period was already delivered; nothing was sent. */
        ALREADY_EXPORTED,
        /** Another attempt holds the batch; nothing was sent by this call. */
        IN_PROGRESS
    }

    static HrExportResult afterAttempt(HrExportBatch batch, DeliveryResult delivery) {
        Outcome outcome = switch (batch.status()) {
            case ACKNOWLEDGED, SENT -> Outcome.DELIVERED;
            case PENDING -> Outcome.RETRY_SCHEDULED;
            case FAILED -> Outcome.FAILED;
            case SENDING -> throw new IllegalStateException("an attempt cannot end in SENDING");
        };
        return new HrExportResult(outcome, batch, delivery);
    }

    static HrExportResult alreadyExported(HrExportBatch batch) {
        return new HrExportResult(Outcome.ALREADY_EXPORTED, batch, null);
    }

    static HrExportResult inProgress(HrExportBatch batch) {
        return new HrExportResult(Outcome.IN_PROGRESS, batch, null);
    }
}
