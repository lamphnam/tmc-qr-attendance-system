package vn.edu.hcmut.tmc.attendance.presentation.dto;

import java.util.List;

/**
 * [DRAFT] per-event ack for mixed batches (docs/API-CONTRACTS.md section 5).
 * Statuses: STORED, ALREADY_EXISTS (both mean "committed"), RETRYABLE, REJECTED_PERMANENT.
 */
public record BatchSyncResponseDto(List<Item> results, List<String> retryableIds, List<String> rejectedIds) {

    public record Item(String attendanceId, String status, String reason) {
    }
}
