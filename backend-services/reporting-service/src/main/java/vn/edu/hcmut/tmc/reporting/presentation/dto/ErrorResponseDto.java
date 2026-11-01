package vn.edu.hcmut.tmc.reporting.presentation.dto;

import java.util.UUID;
import java.util.regex.Pattern;

/** Error shape from docs/API-CONTRACTS.md conventions: {@code {code, message, correlationId}}. Never carries stack traces or payloads. */
public record ErrorResponseDto(String code, String message, String correlationId) {

    private static final Pattern SAFE_CORRELATION_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    /** Reuses the gateway's {@code X-Request-Id} when it is well-formed, otherwise mints one. */
    public static ErrorResponseDto of(String code, String message, String requestIdHeader) {
        String correlationId = requestIdHeader != null && SAFE_CORRELATION_ID.matcher(requestIdHeader).matches()
                ? requestIdHeader
                : UUID.randomUUID().toString();
        return new ErrorResponseDto(code, message, correlationId);
    }
}
