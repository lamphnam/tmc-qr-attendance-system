package vn.edu.hcmut.tmc.userqr.presentation.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record QrActionRequestDto(@Pattern(regexp = "[A-Za-z0-9._@-]{1,64}") String actorId,
                                 @Pattern(regexp = "[A-Z_]{1,32}") String actorRole,
                                 @Size(max = 256) String reason) {
    public static QrActionRequestDto empty() { return new QrActionRequestDto(null, null, null); }

    @Override
    public String toString() { return "QrActionRequestDto[REDACTED]"; }
}
