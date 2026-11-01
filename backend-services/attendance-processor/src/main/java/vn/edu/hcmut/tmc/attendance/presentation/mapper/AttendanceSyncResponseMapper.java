package vn.edu.hcmut.tmc.attendance.presentation.mapper;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import vn.edu.hcmut.tmc.attendance.application.service.IngestionResult;
import vn.edu.hcmut.tmc.attendance.presentation.dto.BatchSyncResponseDto;
import vn.edu.hcmut.tmc.attendance.presentation.dto.EventAckDto;

/** Translates application results to the wire acks. Kept out of the controller so the mapping is unit-testable. */
@Component
public class AttendanceSyncResponseMapper {

    public EventAckDto toAck(IngestionResult result) {
        return new EventAckDto(result.attendanceId(), result.status().name(),
                result.status() == IngestionResult.Status.ALREADY_EXISTS);
    }

    public BatchSyncResponseDto toBatchResponse(List<IngestionResult> results) {
        List<BatchSyncResponseDto.Item> items = new ArrayList<>(results.size());
        List<String> retryable = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        for (IngestionResult r : results) {
            String wireStatus = switch (r.status()) {
                case STORED -> "STORED";
                case ALREADY_EXISTS -> "ALREADY_EXISTS";
                case RETRYABLE -> "RETRYABLE";
                case CONFLICT, REJECTED -> "REJECTED_PERMANENT";
            };
            items.add(new BatchSyncResponseDto.Item(r.attendanceId(), wireStatus, r.reason()));
            if (r.status() == IngestionResult.Status.RETRYABLE) {
                retryable.add(r.attendanceId());
            } else if (r.status() == IngestionResult.Status.CONFLICT || r.status() == IngestionResult.Status.REJECTED) {
                rejected.add(r.attendanceId());
            }
        }
        return new BatchSyncResponseDto(items, retryable, rejected);
    }
}
