package vn.edu.hcmut.tmc.kiosk.domain.service;

import java.time.Duration;

/** Exponential backoff with a ceiling. Pure function of the number of attempts already made. */
public class RetryBackoffPolicy {

    private final Duration base;
    private final Duration max;

    public RetryBackoffPolicy(Duration base, Duration max) {
        if (base.isNegative() || base.isZero() || max.compareTo(base) < 0) {
            throw new IllegalArgumentException("require 0 < base <= max");
        }
        this.base = base;
        this.max = max;
    }

    /** @param priorAttempts how many sync attempts have already failed for this event (0 for the first retry) */
    public Duration delayFor(int priorAttempts) {
        int exponent = Math.min(Math.max(priorAttempts, 0), 20);
        long millis = base.toMillis() << exponent;
        return Duration.ofMillis(Math.min(millis, max.toMillis()));
    }
}
