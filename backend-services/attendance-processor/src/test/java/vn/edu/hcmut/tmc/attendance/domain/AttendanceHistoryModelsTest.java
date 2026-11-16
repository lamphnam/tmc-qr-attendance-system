package vn.edu.hcmut.tmc.attendance.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import vn.edu.hcmut.tmc.attendance.domain.model.AttendanceHistoryPage;
import vn.edu.hcmut.tmc.attendance.domain.model.AttendanceHistoryQuery;
import vn.edu.hcmut.tmc.attendance.domain.model.AttendanceRecord;
import vn.edu.hcmut.tmc.attendance.domain.model.Direction;
import vn.edu.hcmut.tmc.attendance.domain.model.ScanResult;

class AttendanceHistoryModelsTest {

    private static final Instant FROM = Instant.parse("2026-11-14T01:00:00Z");
    private static final Instant TO = FROM.plusSeconds(60);

    private static AttendanceRecord event() {
        return new AttendanceRecord(UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "EMP001", "BD", "BD-G01", Direction.IN, FROM, ScanResult.ALLOWED, "BD-K01", false);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "\t\r\n"})
    void rejectsMissingEmployeeFilter(String employeeId) {
        assertThatThrownBy(() -> new AttendanceHistoryQuery(employeeId, "BD", FROM, TO, 0, 20))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("employeeId must not be blank");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "\t\r\n"})
    void rejectsMissingFactoryScope(String factoryId) {
        assertThatThrownBy(() -> new AttendanceHistoryQuery("EMP001", factoryId, FROM, TO, 0, 20))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("factoryId must not be blank");
    }

    @Test
    void normalizesIdsWithoutChangingTheirCase() {
        AttendanceHistoryQuery query = new AttendanceHistoryQuery(" Emp001 ", " Bd ", FROM, TO, 0, 1);

        assertThat(query.employeeId()).isEqualTo("Emp001");
        assertThat(query.factoryId()).isEqualTo("Bd");
        assertThat(query.fromInclusive()).isEqualTo(FROM);
        assertThat(query.toExclusive()).isEqualTo(TO);
    }

    @Test
    void rejectsMissingEqualOrReversedTimeBounds() {
        assertThatThrownBy(() -> new AttendanceHistoryQuery("EMP001", "BD", null, TO, 0, 20))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AttendanceHistoryQuery("EMP001", "BD", FROM, null, 0, 20))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AttendanceHistoryQuery("EMP001", "BD", FROM, FROM, 0, 20))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AttendanceHistoryQuery("EMP001", "BD", TO, FROM, 0, 20))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsSubMillisecondBoundsInsteadOfSilentlyChangingTheRange() {
        assertThatThrownBy(() -> new AttendanceHistoryQuery("EMP001", "BD", FROM.plusNanos(1), TO, 0, 20))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("time bounds must have millisecond precision");
        assertThatThrownBy(() -> new AttendanceHistoryQuery("EMP001", "BD", FROM, TO.plusNanos(1), 0, 20))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("time bounds must have millisecond precision");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 101, Integer.MAX_VALUE})
    void rejectsUnboundedOrInvalidPageSizes(int pageSize) {
        assertThatThrownBy(() -> new AttendanceHistoryQuery("EMP001", "BD", FROM, TO, 0, pageSize))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, Integer.MIN_VALUE})
    void rejectsNegativePageNumbers(int pageNumber) {
        assertThatThrownBy(() -> new AttendanceHistoryQuery("EMP001", "BD", FROM, TO, pageNumber, 20))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsTheMaximumPageSizeAndComputesOffsetsWithoutIntegerOverflow() {
        assertThat(new AttendanceHistoryQuery("EMP001", "BD", FROM, TO, 0, 100).offset()).isZero();
        assertThat(new AttendanceHistoryQuery("EMP001", "BD", FROM, TO, 2, 20).offset()).isEqualTo(40L);
        assertThat(new AttendanceHistoryQuery("EMP001", "BD", FROM, TO, Integer.MAX_VALUE, 100).offset())
                .isEqualTo(214748364700L);
    }

    @Test
    void pageCopiesTheListAndDoesNotExposeMutableContents() {
        List<AttendanceRecord> input = new ArrayList<>(List.of(event()));
        AttendanceHistoryPage page = new AttendanceHistoryPage(input, 0, 1, true);
        input.clear();

        assertThat(page.events()).containsExactly(event());
        assertThatThrownBy(() -> page.events().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void emptyPagesPreserveRequestedPaginationAndHaveNoNextPage() {
        AttendanceHistoryPage page = new AttendanceHistoryPage(List.of(), 3, 20, false);

        assertThat(page.events()).isEmpty();
        assertThat(page.pageNumber()).isEqualTo(3);
        assertThat(page.pageSize()).isEqualTo(20);
        assertThat(page.hasNext()).isFalse();
    }

    @Test
    void rejectsInconsistentOrInvalidPageContents() {
        assertThatThrownBy(() -> new AttendanceHistoryPage(null, 0, 20, false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AttendanceHistoryPage(List.of(), -1, 20, false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AttendanceHistoryPage(List.of(), 0, 0, false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AttendanceHistoryPage(List.of(), 0, 101, false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AttendanceHistoryPage(List.of(event(), event()), 0, 1, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AttendanceHistoryPage(List.of(event()), 0, 20, true))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
