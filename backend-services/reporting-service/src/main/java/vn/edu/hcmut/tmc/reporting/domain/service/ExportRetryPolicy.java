package vn.edu.hcmut.tmc.reporting.domain.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * How long to wait between attempts and when to give up (NFR-6). Exponential backoff with a ceiling; no attempt is
 * scheduled after the batch's retry deadline, which is {@code createdAt + retryWindow}: the 01:00 run with a 5 h window
 * stops at 06:00, leaving time for a manual export before the 12:00 reconciliation.
 */
public class ExportRetryPolicy {

    private final Duration base;
    private final Duration max;
    private final Duration retryWindow;

    public ExportRetryPolicy(Duration base, Duration max, Duration retryWindow) {
        if (base == null || max == null || retryWindow == null
                || base.isNegative() || base.isZero() || max.compareTo(base) < 0 || retryWindow.isNegative()) {
            throw new IllegalArgumentException("require 0 < base <= max and a non-negative retry window");
        }
        this.base = base;
        this.max = max;
        this.retryWindow = retryWindow;
    }

    public Instant deadlineFor(Instant start) {
        return start.plus(retryWindow);
    }

    /**
     * @param attemptsMade attempts already made, including the one that just failed (1 after the first failure)
     * @return when to try again, or empty when that would be after {@code deadline}: give up and alert
     */
    public Optional<Instant> nextAttemptAfter(int attemptsMade, Instant failedAt, Instant deadline) {
        int exponent = Math.min(Math.max(attemptsMade - 1, 0), 20);
        Duration delay = Duration.ofMillis(Math.min(base.toMillis() << exponent, max.toMillis()));
        Instant next = failedAt.plus(delay);
        return next.isAfter(deadline) ? Optional.empty() : Optional.of(next);
    }
}
