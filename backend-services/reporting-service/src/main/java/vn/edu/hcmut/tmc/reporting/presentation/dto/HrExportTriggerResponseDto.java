package vn.edu.hcmut.tmc.reporting.presentation.dto;

import vn.edu.hcmut.tmc.reporting.application.service.HrExportResult;

/**
 * {@code outcome}: {@code DELIVERED} or {@code RETRY_SCHEDULED}. {@code duplicateBatch} is the receiver's own report that
 * it already held this batch (null when nothing was sent).
 */
public record HrExportTriggerResponseDto(String outcome, Boolean duplicateBatch, HrExportBatchDto batch) {

    public static HrExportTriggerResponseDto from(HrExportResult result) {
        return new HrExportTriggerResponseDto(result.outcome().name(),
                result.delivery() == null ? null : result.delivery().duplicateBatch(),
                HrExportBatchDto.from(result.batch()));
    }
}
