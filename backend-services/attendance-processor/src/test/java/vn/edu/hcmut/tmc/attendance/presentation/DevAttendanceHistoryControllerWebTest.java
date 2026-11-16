package vn.edu.hcmut.tmc.attendance.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.util.LinkedMultiValueMap;

import vn.edu.hcmut.tmc.attendance.application.service.AttendanceHistoryService;
import vn.edu.hcmut.tmc.attendance.domain.exception.AttendanceStorageException;
import vn.edu.hcmut.tmc.attendance.domain.model.AttendanceHistoryPage;
import vn.edu.hcmut.tmc.attendance.domain.model.AttendanceHistoryQuery;
import vn.edu.hcmut.tmc.attendance.domain.model.AttendanceRecord;
import vn.edu.hcmut.tmc.attendance.domain.model.Direction;
import vn.edu.hcmut.tmc.attendance.domain.model.ScanResult;
import vn.edu.hcmut.tmc.attendance.domain.repository.IAttendanceHistoryQuery;
import vn.edu.hcmut.tmc.attendance.presentation.controller.DevAttendanceHistoryController;

@WebMvcTest(DevAttendanceHistoryController.class)
@Import(AttendanceHistoryService.class)
@ActiveProfiles("dev")
class DevAttendanceHistoryControllerWebTest {

    private static final String FROM = "2026-11-14T01:00:00Z";
    private static final String TO = "2026-11-14T01:01:00Z";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    IAttendanceHistoryQuery query;

    private static MockHttpServletRequestBuilder request() {
        return request(null, null);
    }

    private static MockHttpServletRequestBuilder request(String field, String value) {
        var params = new LinkedMultiValueMap<String, String>();
        params.set("employeeId", "EMP001");
        params.set("factoryId", "BD");
        params.set("fromInclusive", FROM);
        params.set("toExclusive", TO);
        if (field != null) {
            if (value == null) {
                params.remove(field);
            } else {
                params.set(field, value);
            }
        }
        return get("/api/dev/attendance/history").params(params);
    }

    @Test
    void mapsAValidatedRequestAndPageWithoutExposingPersistenceMetadata() throws Exception {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
        AttendanceRecord event = new AttendanceRecord(id, "EMP001", "BD", "BD-G01", Direction.OUT,
                Instant.parse(FROM), ScanResult.REJECTED, "BD-K01", false);
        when(query.find(any())).thenReturn(new AttendanceHistoryPage(List.of(event), 2, 1, true));

        mvc.perform(request().param("page", "2").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-TMC-Dev-Only", "true"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.page").value(2)).andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.hasNext").value(true))
                .andExpect(jsonPath("$.events.length()").value(1))
                .andExpect(jsonPath("$.events[0].attendanceId").value(id.toString()))
                .andExpect(jsonPath("$.events[0].employeeId").value("EMP001"))
                .andExpect(jsonPath("$.events[0].factoryId").value("BD"))
                .andExpect(jsonPath("$.events[0].gateId").value("BD-G01"))
                .andExpect(jsonPath("$.events[0].direction").value("OUT"))
                .andExpect(jsonPath("$.events[0].occurredAt").value(FROM))
                .andExpect(jsonPath("$.events[0].scanResult").value("REJECTED"))
                .andExpect(jsonPath("$.events[0].devFixture").doesNotExist())
                .andExpect(jsonPath("$.events[0].sourceKioskId").doesNotExist());
        verify(query).find(new AttendanceHistoryQuery("EMP001", "BD", Instant.parse(FROM), Instant.parse(TO), 2, 1));
    }

    @Test
    void defaultsToTheFirstPageAndReturnsAnEmptyArray() throws Exception {
        when(query.find(any())).thenReturn(new AttendanceHistoryPage(List.of(), 0, 20, false));

        mvc.perform(request()).andExpect(status().isOk())
                .andExpect(jsonPath("$.events").isEmpty()).andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20)).andExpect(jsonPath("$.hasNext").value(false));
        verify(query).find(new AttendanceHistoryQuery("EMP001", "BD", Instant.parse(FROM), Instant.parse(TO), 0, 20));
    }

    @ParameterizedTest
    @ValueSource(strings = {"employeeId", "factoryId", "fromInclusive", "toExclusive"})
    void missingRequiredParametersNeverReachStorage(String missing) throws Exception {
        expectInvalid(request(missing, null));
    }

    static Stream<Arguments> invalidParameters() {
        return Stream.of(
                Arguments.of("employeeId", ""), Arguments.of("employeeId", " "),
                Arguments.of("factoryId", ""), Arguments.of("factoryId", " "),
                Arguments.of("fromInclusive", "not-a-time-sensitive-input"),
                Arguments.of("toExclusive", "2026-11-31T01:00:00Z"),
                Arguments.of("fromInclusive", "2026-11-14T01:00:00"),
                Arguments.of("fromInclusive", ""), Arguments.of("toExclusive", ""),
                Arguments.of("fromInclusive", TO), Arguments.of("toExclusive", FROM),
                Arguments.of("fromInclusive", "2026-11-14T02:00:00Z"),
                Arguments.of("fromInclusive", "2026-11-14T01:00:00.000001Z"),
                Arguments.of("page", "-1"), Arguments.of("page", "abc"), Arguments.of("page", "2147483648"),
                Arguments.of("size", "0"), Arguments.of("size", "-1"), Arguments.of("size", "101"),
                Arguments.of("size", "abc"), Arguments.of("size", "2147483648"));
    }

    @ParameterizedTest
    @MethodSource("invalidParameters")
    void invalidParametersNeverReachStorageOrEchoTheirValues(String field, String value) throws Exception {
        expectInvalid(request(field, value));
    }

    private void expectInvalid(MockHttpServletRequestBuilder request) throws Exception {
        String body = mvc.perform(request.header("X-Request-Id", "history-invalid"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-TMC-Dev-Only", "true"))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("invalid or missing history query parameters"))
                .andExpect(jsonPath("$.correlationId").value("history-invalid"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("not-a-time-sensitive-input", "stackTrace");
        verifyNoInteractions(query);
    }

    @Test
    void storageFailureIs503WithSafeDiagnosticsRatherThanAnEmptyPage() throws Exception {
        when(query.find(any())).thenThrow(new AttendanceStorageException("synthetic-private-database-detail"));

        String body = mvc.perform(request().header("X-Request-Id", "history-storage"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("STORAGE_UNAVAILABLE"))
                .andExpect(jsonPath("$.correlationId").value("history-storage"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("synthetic-private-database-detail", "events", "stackTrace");
    }

    @Test
    void noProductionHistoryRouteIsRegisteredEvenInDev() throws Exception {
        mvc.perform(get("/api/v1/attendance/history")).andExpect(status().isNotFound());
        verifyNoInteractions(query);
    }
}
