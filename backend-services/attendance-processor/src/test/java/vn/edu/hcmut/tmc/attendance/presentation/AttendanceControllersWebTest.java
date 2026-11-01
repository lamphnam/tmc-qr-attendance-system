package vn.edu.hcmut.tmc.attendance.presentation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import vn.edu.hcmut.tmc.attendance.application.service.AttendanceIngestionService;
import vn.edu.hcmut.tmc.attendance.application.service.IngestionResult;
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
}
