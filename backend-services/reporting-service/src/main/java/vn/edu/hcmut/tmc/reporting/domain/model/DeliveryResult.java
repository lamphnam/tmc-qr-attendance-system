package vn.edu.hcmut.tmc.reporting.domain.model;

/**
 * Result of one send. {@code detail} is a short, safe diagnostic ("HTTP 503", "I/O error (AccessDeniedException)"):
 * never a response body or record content.
 */
public record DeliveryResult(DeliveryOutcome outcome, boolean duplicateBatch, String detail) {

    public DeliveryResult {
        if (outcome == null) {
            throw new IllegalArgumentException("outcome is required");
        }
    }

    public static DeliveryResult acknowledged(boolean duplicateBatch) {
        return new DeliveryResult(DeliveryOutcome.ACKNOWLEDGED, duplicateBatch, null);
    }

    public static DeliveryResult delivered(boolean duplicateBatch) {
        return new DeliveryResult(DeliveryOutcome.DELIVERED, duplicateBatch, null);
    }

    public static DeliveryResult retryable(String detail) {
        return new DeliveryResult(DeliveryOutcome.RETRYABLE, false, detail);
    }

    public static DeliveryResult rejected(String detail) {
        return new DeliveryResult(DeliveryOutcome.REJECTED, false, detail);
    }

    public boolean isSuccess() {
        return outcome == DeliveryOutcome.ACKNOWLEDGED || outcome == DeliveryOutcome.DELIVERED;
    }
}
