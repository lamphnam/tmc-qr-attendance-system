package vn.edu.hcmut.tmc.reporting.presentation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** {@code {"period":"2026-10"}}: the factory-time month to export. */
public record HrExportTriggerRequestDto(
        @NotBlank @Pattern(regexp = "\\d{4}-\\d{2}") String period) {
}
