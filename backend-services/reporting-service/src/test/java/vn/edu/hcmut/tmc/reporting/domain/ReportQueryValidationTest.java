package vn.edu.hcmut.tmc.reporting.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import vn.edu.hcmut.tmc.reporting.domain.model.AttendanceReportQuery;
import vn.edu.hcmut.tmc.reporting.domain.model.DashboardQuery;
import vn.edu.hcmut.tmc.reporting.domain.model.DataScope;
import vn.edu.hcmut.tmc.reporting.domain.model.FactoryCalendar;

class ReportQueryValidationTest {

    private static final DataScope SCOPE = DataScope.productionOnly();
    private static final LocalDate D = LocalDate.of(2026, 10, 1);

    @Test
    void aThirtyOneDayReportIsAcceptedAndThirtyTwoIsNot() {
        assertThat(new AttendanceReportQuery("BD", D, D.plusDays(30), null, 0, 100, SCOPE).to()).isEqualTo(D.plusDays(30));
        assertThatThrownBy(() -> new AttendanceReportQuery("BD", D, D.plusDays(31), null, 0, 100, SCOPE))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("31 days");
    }

    @Test
    void invalidFactoryRangeOrPageIsRejected() {
        assertThatThrownBy(() -> new AttendanceReportQuery("B D", D, D, null, 0, 100, SCOPE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AttendanceReportQuery("BD", D, D.minusDays(1), null, 0, 100, SCOPE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AttendanceReportQuery("BD", D, D, null, -1, 100, SCOPE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AttendanceReportQuery("BD", D, D, null, 0, 501, SCOPE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AttendanceReportQuery("BD", D, D, " ", 0, 100, SCOPE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DashboardQuery("x;drop", D, SCOPE)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void factoryDaysAreVietnameseDays() {
        assertThat(FactoryCalendar.startOf(D)).isEqualTo(Instant.parse("2026-09-30T17:00:00Z"));
        assertThat(FactoryCalendar.endOf(D)).isEqualTo(Instant.parse("2026-10-01T17:00:00Z"));
        assertThat(FactoryCalendar.dateOf(Instant.parse("2026-10-01T17:30:00Z"))).isEqualTo(D.plusDays(1));
    }
}
