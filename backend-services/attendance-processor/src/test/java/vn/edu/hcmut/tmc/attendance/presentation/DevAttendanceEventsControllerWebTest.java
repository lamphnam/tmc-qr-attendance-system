package vn.edu.hcmut.tmc.attendance.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import vn.edu.hcmut.tmc.attendance.application.service.AttendanceEventsService;
import vn.edu.hcmut.tmc.attendance.domain.exception.AttendanceStorageException;
import vn.edu.hcmut.tmc.attendance.domain.model.AttendanceRecord;
import vn.edu.hcmut.tmc.attendance.domain.model.Direction;
import vn.edu.hcmut.tmc.attendance.domain.model.ScanResult;
import vn.edu.hcmut.tmc.attendance.domain.repository.IAttendanceEventsQuery;
import vn.edu.hcmut.tmc.attendance.presentation.controller.AttendanceController;
import vn.edu.hcmut.tmc.attendance.presentation.controller.DevAttendanceEventsController;

@WebMvcTest({DevAttendanceEventsController.class, AttendanceController.class})
@Import(AttendanceEventsService.class)
@ActiveProfiles("dev")
class DevAttendanceEventsControllerWebTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoBean IAttendanceEventsQuery events;

    @Test
    void defaultListMatchesTheSevenFieldsConsumedByFrontendCentralEvent() throws Exception {
        var record = new AttendanceRecord(UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "EMP-001", "BD", "BD-G01", Direction.IN, Instant.parse("2026-11-17T01:00:00Z"),
                ScanResult.ALLOWED, "BD-K01", true);
        when(events.findRecent(20, null)).thenReturn(List.of(record));

        String body = mvc.perform(get("/api/v1/attendance/events"))
                .andExpect(status().isOk()).andExpect(header().string("X-TMC-Dev-Only", "true"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.events.length()").value(1))
                .andExpect(jsonPath("$.events[0].attendanceId").value(record.attendanceId().toString()))
                .andExpect(jsonPath("$.events[0].employeeId").value("EMP-001"))
                .andExpect(jsonPath("$.events[0].factoryId").value("BD"))
                .andExpect(jsonPath("$.events[0].gateId").value("BD-G01"))
                .andExpect(jsonPath("$.events[0].direction").value("IN"))
                .andExpect(jsonPath("$.events[0].occurredAt").value("2026-11-17T01:00:00Z"))
                .andExpect(jsonPath("$.events[0].scanResult").value("ALLOWED"))
                .andReturn().getResponse().getContentAsString();
        // Keep the wire DTO aligned with web-frontend/src/api/types.ts CentralEvent, without storage internals.
        var event = json.readTree(body).get("events").get(0);
        Set<String> fields = StreamSupport.stream(((Iterable<String>) event::fieldNames).spliterator(), false)
                .collect(Collectors.toSet());
        assertThat(fields).containsExactlyInAnyOrder("attendanceId", "employeeId", "factoryId", "gateId",
                "direction", "occurredAt", "scanResult");
        verify(events).findRecent(20, null);
    }

    @Test
    void optionalEmployeeFilterIsNormalizedAndEmptyResultIsAnArray() throws Exception {
        when(events.findRecent(100, "EMP-001")).thenReturn(List.of());
        mvc.perform(get("/api/v1/attendance/events").param("limit", "100").param("employeeId", " EMP-001 "))
                .andExpect(status().isOk()).andExpect(jsonPath("$.events").isArray())
                .andExpect(jsonPath("$.events").isEmpty());
        verify(events).findRecent(100, "EMP-001");
    }

    @ParameterizedTest
    @CsvSource(value = {"limit|0", "limit|-1", "limit|101", "limit|not-a-number", "limit|2147483648",
            "employeeId| ", "employeeId|ABCDEFGHIJKLMNOPQRSTUVWXYZ1234567"}, delimiter = '|', ignoreLeadingAndTrailingWhitespace = false)
    void invalidInputDoesNotReachDatabaseOrEchoValues(String field, String value) throws Exception {
        mvc.perform(get("/api/v1/attendance/events").param(field, value).header("X-Request-Id", "h1-validation"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("invalid attendance event query parameters"))
                .andExpect(jsonPath("$.correlationId").value("h1-validation"));
        verifyNoInteractions(events);
    }

    @Test
    void explicitlyEmptyEmployeeFilterIsRejected() throws Exception {
        mvc.perform(get("/api/v1/attendance/events").param("employeeId", ""))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(events);
    }

    @Test
    void storageOutageIs503AndNeverASuccessfulEmptyList() throws Exception {
        when(events.findRecent(anyInt(), isNull())).thenThrow(new AttendanceStorageException("private driver detail"));
        String body = mvc.perform(get("/api/v1/attendance/events"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("STORAGE_UNAVAILABLE"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("private driver detail", "events", "stackTrace");
    }

    @Test
    void productionPostRoutesRemain501AndDoNotInvokeTheReadPort() throws Exception {
        for (String path : List.of("/api/v1/attendance/events", "/api/v1/attendance/sync")) {
            mvc.perform(post(path)).andExpect(status().isNotImplemented());
        }
        verifyNoInteractions(events);
    }
}
