package vn.edu.hcmut.tmc.attendance.presentation.dto;

import java.util.List;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * [DRAFT] docs/API-CONTRACTS.md section 5. Elements are deliberately NOT cascade-validated here: each event is
 * validated individually so one malformed event is rejected alone and cannot sink its siblings.
 */
public record BatchSyncRequestDto(
        String kioskId,
        @NotNull @NotEmpty @Size(max = 500) List<AttendanceEventDto> events) {
}
