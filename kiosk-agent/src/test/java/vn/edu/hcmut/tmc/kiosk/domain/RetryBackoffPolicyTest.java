package vn.edu.hcmut.tmc.kiosk.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import vn.edu.hcmut.tmc.kiosk.domain.service.RetryBackoffPolicy;

class RetryBackoffPolicyTest {

    private final RetryBackoffPolicy policy = new RetryBackoffPolicy(Duration.ofSeconds(2), Duration.ofSeconds(60));

    @Test
    void doublesEachAttemptUntilTheCeiling() {
        assertThat(policy.delayFor(0)).isEqualTo(Duration.ofSeconds(2));
        assertThat(policy.delayFor(1)).isEqualTo(Duration.ofSeconds(4));
        assertThat(policy.delayFor(2)).isEqualTo(Duration.ofSeconds(8));
        assertThat(policy.delayFor(5)).isEqualTo(Duration.ofSeconds(60));
        assertThat(policy.delayFor(10_000)).as("no overflow for absurd retry counts").isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void negativeAttemptsAreTreatedAsTheFirst() {
        assertThat(policy.delayFor(-3)).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void rejectsNonsenseConfiguration() {
        assertThatThrownBy(() -> new RetryBackoffPolicy(Duration.ZERO, Duration.ofSeconds(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryBackoffPolicy(Duration.ofSeconds(5), Duration.ofSeconds(1))).isInstanceOf(IllegalArgumentException.class);
    }
}
