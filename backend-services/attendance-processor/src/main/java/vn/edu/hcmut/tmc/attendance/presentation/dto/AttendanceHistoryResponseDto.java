package vn.edu.hcmut.tmc.attendance.presentation.dto;

import java.util.List;

import vn.edu.hcmut.tmc.attendance.domain.model.AttendanceHistoryPage;

/** Dev-only page of attendance facts; no totals or cross-request snapshot guarantee. */
public record AttendanceHistoryResponseDto(List<AttendanceEventDto> events, int page, int size, boolean hasNext) {

    public AttendanceHistoryResponseDto {
        events = List.copyOf(events);
    }

    public static AttendanceHistoryResponseDto from(AttendanceHistoryPage page) {
        return new AttendanceHistoryResponseDto(page.events().stream().map(AttendanceEventDto::from).toList(),
                page.pageNumber(), page.pageSize(), page.hasNext());
    }
}
