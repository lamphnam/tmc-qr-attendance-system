package vn.edu.hcmut.tmc.reporting.domain.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;

import vn.edu.hcmut.tmc.reporting.domain.model.ExportPeriod;

/**
 * When a month becomes due for export (NFR-6): on day 1 at {@code runAt} factory time, the previous month is due.
 *
 * <p>This is a pure rule over "now" instead of a cron trigger, so a run missed while the service was down is caught up
 * by the next poll, and a fake clock can test the 00:59 / 01:00 boundary.
 */
public class ExportSchedule {

    private final Duration runAtOffset;

    public ExportSchedule(LocalTime runAt) {
        if (runAt == null) {
            throw new IllegalArgumentException("runAt is required");
        }
        this.runAtOffset = Duration.ofSeconds(runAt.toSecondOfDay());
    }

    /** The newest month whose export time has passed at {@code now}. */
    public ExportPeriod periodDueAt(Instant now) {
        return ExportPeriod.monthContaining(now.minus(runAtOffset)).previous();
    }

    public Instant scheduledAt(ExportPeriod period) {
        return period.endExclusive().plus(runAtOffset);
    }
}
