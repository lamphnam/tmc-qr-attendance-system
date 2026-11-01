package vn.edu.hcmut.tmc.kiosk.application.usecase;

/**
 * What one sync pass actually did. {@code skippedReason} is non-null when nothing was attempted
 * (switch off, another pass running, central unreachable).
 */
public record SyncRunReport(int attempted, int synced, int failed, int retryScheduled, String skippedReason) {

    public static SyncRunReport skipped(String reason) {
        return new SyncRunReport(0, 0, 0, 0, reason);
    }

    public static SyncRunReport idle() {
        return new SyncRunReport(0, 0, 0, 0, null);
    }
}
