package vn.edu.hcmut.tmc.attendance.presentation.dto;

import java.util.List;

import vn.edu.hcmut.tmc.attendance.domain.model.AttendanceRecord;

/** The demo Central column consumes these same seven event fields as dev ingestion/read-back. */
public record AttendanceEventsResponseDto(List<AttendanceEventDto> events) {

    public AttendanceEventsResponseDto {
        events = List.copyOf(events);
    }

    public static AttendanceEventsResponseDto from(List<AttendanceRecord> records) {
        return new AttendanceEventsResponseDto(records.stream().map(AttendanceEventDto::from).toList());
    }
}
