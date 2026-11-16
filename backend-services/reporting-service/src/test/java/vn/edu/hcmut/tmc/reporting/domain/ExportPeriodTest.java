package vn.edu.hcmut.tmc.reporting.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.YearMonth;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import vn.edu.hcmut.tmc.reporting.domain.model.ExportPeriod;

/** D10 / B24: Vietnamese calendar month and a batch id that is a pure function of the month. */
class ExportPeriodTest {

    private static final ExportPeriod OCTOBER = new ExportPeriod(YearMonth.of(2026, 10));

    @Test
    void monthBoundariesAreVietnameseMidnightsNotUtcMidnights() {
        assertThat(OCTOBER.startInclusive()).isEqualTo(Instant.parse("2026-09-30T17:00:00Z"));
        assertThat(OCTOBER.endExclusive()).isEqualTo(Instant.parse("2026-10-31T17:00:00Z"));
    }

    @Test
    void aScanAt0630OnTheFirstInVietnamBelongsToTheNewMonth() {
        Instant scan = Instant.parse("2026-10-31T23:30:00Z"); // 1 November 06:30 in Vietnam
        assertThat(ExportPeriod.monthContaining(scan).label()).isEqualTo("2026-11");
    }

    @Test
    void batchUuidIsDeterministicPerPeriodAndDiffersBetweenPeriods() {
        UUID expected = UUID.nameUUIDFromBytes("tmc-hr-export:2026-10".getBytes(StandardCharsets.UTF_8));

        assertThat(OCTOBER.batchUuid()).isEqualTo(expected);
        assertThat(ExportPeriod.parse("2026-10").batchUuid()).isEqualTo(expected);
        assertThat(ExportPeriod.parse("2026-09").batchUuid()).isNotEqualTo(expected);
    }

    @Test
    void aMonthIsCompleteOnlyFromItsVietnameseEnd() {
        assertThat(OCTOBER.isCompleteAt(Instant.parse("2026-10-31T16:59:59Z"))).isFalse();
        assertThat(OCTOBER.isCompleteAt(Instant.parse("2026-10-31T17:00:00Z"))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-13", "2026-1", "26-10", "2026/10", "", "1999-12", "abcd-ef"})
    void malformedOrOutOfRangePeriodsAreRejected(String text) {
        assertThatThrownBy(() -> ExportPeriod.parse(text)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullPeriodTextIsRejected() {
        assertThatThrownBy(() -> ExportPeriod.parse(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
