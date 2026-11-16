package vn.edu.hcmut.tmc.reporting.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;

import org.junit.jupiter.api.Test;

import vn.edu.hcmut.tmc.reporting.domain.service.ExportRetryPolicy;
import vn.edu.hcmut.tmc.reporting.domain.service.ExportSchedule;

/** NFR-6 timing rules as pure functions of "now": no Spring, no clock, no database. */
class ExportScheduleAndRetryPolicyTest {

    private final ExportSchedule schedule = new ExportSchedule(LocalTime.of(1, 0));
    private final ExportRetryPolicy policy =
            new ExportRetryPolicy(Duration.ofMinutes(1), Duration.ofMinutes(30), Duration.ofHours(5));

    @Test
    void octoberBecomesDueAt0100OnTheFirstOfNovemberVietnamTime() {
        assertThat(schedule.periodDueAt(Instant.parse("2026-10-31T17:59:59Z")).label())  // 1 Nov 00:59:59 VN
                .isEqualTo("2026-09");
        assertThat(schedule.periodDueAt(Instant.parse("2026-10-31T18:00:00Z")).label())  // 1 Nov 01:00 VN
                .isEqualTo("2026-10");
        assertThat(schedule.periodDueAt(Instant.parse("2026-11-15T03:00:00Z")).label())  // mid-month: still October
                .isEqualTo("2026-10");
    }

    @Test
    void januaryRunExportsDecemberOfThePreviousYear() {
        assertThat(schedule.periodDueAt(Instant.parse("2026-12-31T18:00:00Z")).label()).isEqualTo("2026-12");
    }

    @Test
    void backoffDoublesFromOneMinuteAndIsCappedAtThirtyMinutes() {
        Instant failedAt = Instant.parse("2026-10-31T18:00:00Z");
        Instant deadline = policy.deadlineFor(failedAt);

        assertThat(policy.nextAttemptAfter(1, failedAt, deadline)).contains(failedAt.plus(Duration.ofMinutes(1)));
        assertThat(policy.nextAttemptAfter(2, failedAt, deadline)).contains(failedAt.plus(Duration.ofMinutes(2)));
        assertThat(policy.nextAttemptAfter(5, failedAt, deadline)).contains(failedAt.plus(Duration.ofMinutes(16)));
        assertThat(policy.nextAttemptAfter(6, failedAt, deadline)).contains(failedAt.plus(Duration.ofMinutes(30)));
        assertThat(policy.nextAttemptAfter(40, failedAt, deadline)).contains(failedAt.plus(Duration.ofMinutes(30)));
    }

    @Test
    void the0100RunStopsRetryingAt0600AndNeverSchedulesPastIt() {
        Instant createdAt = Instant.parse("2026-10-31T18:00:00Z");       // 01:00 VN
        Instant deadline = policy.deadlineFor(createdAt);
        assertThat(deadline).isEqualTo(Instant.parse("2026-10-31T23:00:00Z")); // 06:00 VN

        assertThat(policy.nextAttemptAfter(1, deadline.minusSeconds(60), deadline)).contains(deadline);
        assertThat(policy.nextAttemptAfter(1, deadline.minusSeconds(59), deadline)).isEmpty();
    }

    @Test
    void invalidPolicyConfigurationIsRejected() {
        assertThatThrownBy(() -> new ExportRetryPolicy(Duration.ZERO, Duration.ofMinutes(1), Duration.ofHours(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExportRetryPolicy(Duration.ofMinutes(5), Duration.ofMinutes(1), Duration.ofHours(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
