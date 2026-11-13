package vn.edu.hcmut.tmc.attendance.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import vn.edu.hcmut.tmc.attendance.application.service.AttendanceIngestionService;
import vn.edu.hcmut.tmc.attendance.application.service.IngestionResult;
import vn.edu.hcmut.tmc.attendance.domain.model.AttendanceRecord;
import vn.edu.hcmut.tmc.attendance.presentation.controller.ApiExceptionHandler;
import vn.edu.hcmut.tmc.attendance.presentation.controller.AttendanceController;
import vn.edu.hcmut.tmc.attendance.presentation.controller.DevAttendanceController;
import vn.edu.hcmut.tmc.attendance.presentation.mapper.AttendanceSyncResponseMapper;

@WebMvcTest(controllers = {AttendanceController.class, DevAttendanceController.class})
@Import({ApiExceptionHandler.class, AttendanceSyncResponseMapper.class})
@ActiveProfiles("dev")
class AttendanceControllersWebTest {

    private static final String ID = "c5b0ff1a-2c59-4333-ac72-1b41b84e84a1";

    private static final String VALID_EVENT = """
            {"attendanceId":"%s","employeeId":"EMP001","factoryId":"BD","gateId":"BD-G01",
             "direction":"IN","occurredAt":"2026-11-01T01:20:00Z","scanResult":"ALLOWED"}
            """.formatted(ID);

    @Autowired
    MockMvc mvc;

    @MockitoBean
    AttendanceIngestionService service;

    @Test
    void productionRoutesHonestlyAnswerNotImplemented() throws Exception {
        for (String path : new String[] {"/api/v1/attendance/events", "/api/v1/attendance/sync"}) {
            mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(VALID_EVENT))
                    .andExpect(status().isNotImplemented())
                    .andExpect(jsonPath("$.code").value("NOT_IMPLEMENTED"));
        }
        verify(service, never()).ingest(any());
    }

    @Test
    void devEndpointAcksStoredEventAndIsLabelledDevOnly() throws Exception {
        given(service.ingest(any())).willReturn(IngestionResult.stored(ID));

        mvc.perform(post("/api/dev/attendance/events").contentType(MediaType.APPLICATION_JSON).content(VALID_EVENT))
                .andExpect(status().isOk())
                .andExpect(header().string("X-TMC-Dev-Only", "true"))
                .andExpect(jsonPath("$.attendanceId").value(ID))
                .andExpect(jsonPath("$.status").value("STORED"))
                .andExpect(jsonPath("$.duplicate").value(false));
    }

    @Test
    void retryOfSameEventIsAckedAsDuplicate() throws Exception {
        given(service.ingest(any())).willReturn(IngestionResult.alreadyExists(ID));

        mvc.perform(post("/api/dev/attendance/events").contentType(MediaType.APPLICATION_JSON).content(VALID_EVENT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ALREADY_EXISTS"))
                .andExpect(jsonPath("$.duplicate").value(true));
    }

    @Test
    void conflictingPayloadIsA409WithErrorShape() throws Exception {
        given(service.ingest(any())).willReturn(IngestionResult.conflict(ID));

        mvc.perform(post("/api/dev/attendance/events").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Request-Id", "req-123").content(VALID_EVENT))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ATTENDANCE_ID_CONFLICT"))
                .andExpect(jsonPath("$.correlationId").value("req-123"));
    }

    @Test
    void storageOutageIsA503SoTheKioskKeepsTheEventPending() throws Exception {
        given(service.ingest(any())).willReturn(IngestionResult.retryable(ID, "central storage unavailable"));

        mvc.perform(post("/api/dev/attendance/events").contentType(MediaType.APPLICATION_JSON).content(VALID_EVENT))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("STORAGE_UNAVAILABLE"));
    }

    @Test
    void malformedUuidIsRejectedBeforeReachingTheService() throws Exception {
        mvc.perform(post("/api/dev/attendance/events").contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_EVENT.replace(ID, "not-a-uuid")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_EVENT"));
        verify(service, never()).ingest(any());
    }

    @Test
    void missingFieldsFailBeanValidation() throws Exception {
        mvc.perform(post("/api/dev/attendance/events").contentType(MediaType.APPLICATION_JSON).content("{\"attendanceId\":\"" + ID + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void batchRejectsOneBadEventAloneAndStillProcessesTheRest() throws Exception {
        given(service.ingest(any())).willReturn(IngestionResult.stored(ID));
        String batch = """
                {"kioskId":"BD-K01","events":[%s,
                  {"attendanceId":"nope","employeeId":"EMP001","factoryId":"BD","gateId":"BD-G01",
                   "direction":"IN","occurredAt":"2026-11-01T01:20:00Z","scanResult":"ALLOWED"}]}
                """.formatted(VALID_EVENT);

        mvc.perform(post("/api/dev/attendance/sync").contentType(MediaType.APPLICATION_JSON).content(batch))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].status").value("STORED"))
                .andExpect(jsonPath("$.results[1].status").value("REJECTED_PERMANENT"))
                .andExpect(jsonPath("$.rejectedIds[0]").value("nope"));
        verify(service, times(1)).ingest(any());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void nullElementIsRejectedAloneAtAnyPositionWithoutHidingValidNeighbors(int nullIndex) throws Exception {
        String otherId = "f15ef836-80cd-42f7-a50e-a59e8762929d";
        List<String> events = new ArrayList<>(List.of(VALID_EVENT, VALID_EVENT.replace(ID, otherId)));
        events.add(nullIndex, "null");
        given(service.ingest(any())).willAnswer(call -> {
            AttendanceRecord record = call.getArgument(0);
            return IngestionResult.stored(record.attendanceId().toString());
        });

        var response = mvc.perform(post("/api/dev/attendance/sync").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kioskId\":\"BD-K01\",\"events\":[" + String.join(",", events) + "]}"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-TMC-Dev-Only", "true"))
                .andExpect(jsonPath("$.results.length()").value(3))
                .andExpect(jsonPath("$.results[" + nullIndex + "].status").value("REJECTED_PERMANENT"))
                .andExpect(jsonPath("$.rejectedIds[0]").value("null"))
                .andExpect(jsonPath("$.rejectedIds.length()").value(1))
                .andExpect(jsonPath("$.retryableIds").isEmpty());
        for (int index = 0; index < events.size(); index++) {
            if (index != nullIndex) {
                response.andExpect(jsonPath("$.results[" + index + "].status").value("STORED"));
            }
        }
        ArgumentCaptor<AttendanceRecord> records = ArgumentCaptor.forClass(AttendanceRecord.class);
        verify(service, times(2)).ingest(records.capture());
        assertThat(records.getAllValues()).extracting(record -> record.attendanceId().toString()).containsExactly(ID, otherId);
    }

    @Test
    void batchContainingOnlyNullHasNoSuccessfulAckAndNeverCallsIngestion() throws Exception {
        mvc.perform(post("/api/dev/attendance/sync").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kioskId\":\"BD-K01\",\"events\":[null]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results.length()").value(1))
                .andExpect(jsonPath("$.results[0].status").value("REJECTED_PERMANENT"))
                .andExpect(jsonPath("$.rejectedIds[0]").value("null"))
                .andExpect(jsonPath("$.retryableIds").isEmpty());
        verify(service, never()).ingest(any());
    }

    @Test
    void mixedBatchKeepsProcessingValidEventsAfterDifferentValidationFailures() throws Exception {
        String secondId = "f15ef836-80cd-42f7-a50e-a59e8762929d";
        String thirdId = "03d358f9-5f74-47fb-9858-529785c2cc63";
        String badDirectionId = "70fdb8fd-35e1-412f-be7f-97c6f8cb94db";
        String badTimestampId = "bf3953a0-89b5-4d52-b8d4-23bc4335acbf";
        List<String> events = List.of(VALID_EVENT, VALID_EVENT.replace(ID, "invalid-uuid"), "{}",
                VALID_EVENT.replace(ID, secondId),
                VALID_EVENT.replace(ID, badDirectionId).replace("\"IN\"", "\"SIDEWAYS\""),
                VALID_EVENT.replace(ID, badTimestampId).replace("2026-11-01T01:20:00Z", "invalid-time"),
                VALID_EVENT.replace(ID, thirdId));
        given(service.ingest(any())).willAnswer(call -> {
            AttendanceRecord record = call.getArgument(0);
            return IngestionResult.stored(record.attendanceId().toString());
        });

        var response = mvc.perform(post("/api/dev/attendance/sync").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kioskId\":\"BD-K01\",\"events\":[" + String.join(",", events) + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results.length()").value(7))
                .andExpect(jsonPath("$.rejectedIds.length()").value(4))
                .andExpect(jsonPath("$.retryableIds").isEmpty());
        List<String> expected = List.of("STORED", "REJECTED_PERMANENT", "REJECTED_PERMANENT", "STORED",
                "REJECTED_PERMANENT", "REJECTED_PERMANENT", "STORED");
        for (int index = 0; index < expected.size(); index++) {
            response.andExpect(jsonPath("$.results[" + index + "].status").value(expected.get(index)));
        }
        ArgumentCaptor<AttendanceRecord> records = ArgumentCaptor.forClass(AttendanceRecord.class);
        verify(service, times(3)).ingest(records.capture());
        assertThat(records.getAllValues()).extracting(record -> record.attendanceId().toString()).containsExactly(ID, secondId, thirdId);
        assertThat(records.getAllValues()).allSatisfy(record -> {
            assertThat(record.sourceKioskId()).isEqualTo("BD-K01");
            assertThat(record.devFixture()).isTrue();
        });
    }

    @Test
    void batchMapsDuplicateConflictAndRetryableResultsWithoutTurningThemIntoStoredAcks() throws Exception {
        String retryId = "f15ef836-80cd-42f7-a50e-a59e8762929d";
        String lastId = "03d358f9-5f74-47fb-9858-529785c2cc63";
        given(service.ingest(any())).willReturn(IngestionResult.stored(ID), IngestionResult.alreadyExists(ID),
                IngestionResult.conflict(ID), IngestionResult.retryable(retryId, "central storage unavailable"),
                IngestionResult.stored(lastId));
        String batch = "{\"kioskId\":\"BD-K01\",\"events\":[" + String.join(",", List.of(
                VALID_EVENT, VALID_EVENT, VALID_EVENT.replace("EMP001", "EMP999"),
                VALID_EVENT.replace(ID, retryId), VALID_EVENT.replace(ID, lastId))) + "]}";

        mvc.perform(post("/api/dev/attendance/sync").contentType(MediaType.APPLICATION_JSON).content(batch))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results.length()").value(5))
                .andExpect(jsonPath("$.results[0].status").value("STORED"))
                .andExpect(jsonPath("$.results[1].status").value("ALREADY_EXISTS"))
                .andExpect(jsonPath("$.results[2].status").value("REJECTED_PERMANENT"))
                .andExpect(jsonPath("$.results[3].status").value("RETRYABLE"))
                .andExpect(jsonPath("$.results[4].status").value("STORED"))
                .andExpect(jsonPath("$.rejectedIds.length()").value(1))
                .andExpect(jsonPath("$.rejectedIds[0]").value(ID))
                .andExpect(jsonPath("$.retryableIds.length()").value(1))
                .andExpect(jsonPath("$.retryableIds[0]").value(retryId));
        verify(service, times(5)).ingest(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"events\":null}", "{\"events\":[]}"})
    void invalidBatchEnvelopeIsRejectedBeforeAnyEventIsIngested(String body) throws Exception {
        mvc.perform(post("/api/dev/attendance/sync").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verify(service, never()).ingest(any());
    }
}
