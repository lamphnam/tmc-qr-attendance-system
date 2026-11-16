package vn.edu.hcmut.tmc.attendance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import vn.edu.hcmut.tmc.attendance.application.service.AttendanceHistoryService;
import vn.edu.hcmut.tmc.attendance.domain.exception.AttendanceStorageException;
import vn.edu.hcmut.tmc.attendance.domain.model.AttendanceHistoryPage;
import vn.edu.hcmut.tmc.attendance.domain.model.AttendanceHistoryQuery;
import vn.edu.hcmut.tmc.attendance.domain.model.AttendanceRecord;
import vn.edu.hcmut.tmc.attendance.domain.model.Direction;
import vn.edu.hcmut.tmc.attendance.domain.model.ScanResult;
import vn.edu.hcmut.tmc.attendance.domain.repository.IAttendanceHistoryQuery;

class AttendanceHistoryServiceTest {

    private static final Instant FROM = Instant.parse("2026-11-14T01:00:00Z");
    private static final Instant TO = FROM.plusSeconds(60);

    private IAttendanceHistoryQuery history;
    private AttendanceHistoryService service;

    @BeforeEach
    void setUp() {
        history = mock(IAttendanceHistoryQuery.class);
        service = new AttendanceHistoryService(history);
    }

    @Test
    void passesValidatedScopeRangeAndPaginationToTheReadPort() {
        AttendanceRecord event = new AttendanceRecord(UUID.randomUUID(), "EMP001", "BD", "BD-G01",
                Direction.IN, FROM, ScanResult.ALLOWED, "BD-K01", false);
        AttendanceHistoryPage expected = new AttendanceHistoryPage(List.of(event), 2, 20, false);
        when(history.find(any())).thenReturn(expected);

        assertThat(service.query(" EMP001 ", " BD ", FROM, TO, 2, 20)).isSameAs(expected);
        verify(history).find(new AttendanceHistoryQuery("EMP001", "BD", FROM, TO, 2, 20));
    }

    @Test
    void returnsAnEmptyPageWithoutInventingEvents() {
        AttendanceHistoryPage empty = new AttendanceHistoryPage(List.of(), 0, 20, false);
        when(history.find(any())).thenReturn(empty);

        assertThat(service.query("EMP001", "BD", FROM, TO, 0, 20)).isEqualTo(empty);
    }

    @Test
    void invalidRequestsNeverReachStorage() {
        assertThatThrownBy(() -> service.query(null, "BD", FROM, TO, 0, 20)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.query("EMP001", " ", FROM, TO, 0, 20)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.query("EMP001", "BD", FROM, FROM, 0, 20)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.query("EMP001", "BD", FROM, TO, 0, 101)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(history);
    }

    @Test
    void doesNotDisguiseAStorageFailureAsAnEmptyHistory() {
        AttendanceStorageException unavailable = new AttendanceStorageException("synthetic storage outage");
        when(history.find(any())).thenThrow(unavailable);

        assertThatThrownBy(() -> service.query("EMP001", "BD", FROM, TO, 0, 20)).isSameAs(unavailable);
    }
}
