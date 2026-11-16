package vn.edu.hcmut.tmc.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import vn.edu.hcmut.tmc.attendance.application.service.AttendanceHistoryService;
import vn.edu.hcmut.tmc.attendance.domain.exception.AttendanceStorageException;

/**
 * Boots the full context against a database that is deliberately unreachable, proving that the
 * service starts without a DB, that /actuator/health tells the truth, and that a DB outage becomes a
 * retryable 503 (never a fake success).
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:postgresql://127.0.0.1:1/unreachable",
        "spring.datasource.hikari.connection-timeout=250"
})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class AttendanceProcessorApplicationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    AttendanceHistoryService history;

    @Test
    void healthEndpointReportsDatabaseDownInsteadOfLying() throws Exception {
        String body = mvc.perform(get("/actuator/health"))
                .andExpect(status().isServiceUnavailable())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("\"status\":\"DOWN\"").contains("\"db\":{\"status\":\"DOWN\"");
    }

    @Test
    void historyQueryAgainstAnUnreachableDatabaseThrowsInsteadOfReturningAnEmptyPage() {
        Instant from = Instant.parse("2026-11-14T01:00:00Z");

        assertThatThrownBy(() -> history.query("EMP001", "BD", from, from.plusSeconds(60), 0, 20))
                .isInstanceOf(AttendanceStorageException.class)
                .hasMessage("attendance history storage unavailable");
    }

    @Test
    void historyHttpQueryAgainstAnUnreachableDatabaseReturns503() throws Exception {
        mvc.perform(get("/api/dev/attendance/history").param("employeeId", "EMP001").param("factoryId", "BD")
                        .param("fromInclusive", "2026-11-14T01:00:00Z")
                        .param("toExclusive", "2026-11-14T01:01:00Z"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("STORAGE_UNAVAILABLE"))
                .andExpect(jsonPath("$.events").doesNotExist());
    }

    @Test
    void devIngestionAnswers503WhenCentralDatabaseIsUnreachable() throws Exception {
        mvc.perform(post("/api/dev/attendance/events").contentType(MediaType.APPLICATION_JSON).content("""
                        {"attendanceId":"c5b0ff1a-2c59-4333-ac72-1b41b84e84a1","employeeId":"EMP001","factoryId":"BD",
                         "gateId":"BD-G01","direction":"IN","occurredAt":"2026-11-01T01:20:00Z","scanResult":"ALLOWED"}
                        """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("STORAGE_UNAVAILABLE"));
    }

    @Test
    void databaseOutageInAMixedBatchNeverProducesSuccessfulEventAcks() throws Exception {
        mvc.perform(post("/api/dev/attendance/sync").contentType(MediaType.APPLICATION_JSON).content("""
                        {"kioskId":"BD-K01","events":[
                          {"attendanceId":"c5b0ff1a-2c59-4333-ac72-1b41b84e84a1","employeeId":"EMP001",
                           "factoryId":"BD","gateId":"BD-G01","direction":"IN",
                           "occurredAt":"2026-11-01T01:20:00Z","scanResult":"ALLOWED"},
                          {"attendanceId":"invalid-uuid"},
                          {"attendanceId":"f15ef836-80cd-42f7-a50e-a59e8762929d","employeeId":"EMP002",
                           "factoryId":"BD","gateId":"BD-G01","direction":"OUT",
                           "occurredAt":"2026-11-01T01:21:00Z","scanResult":"ALLOWED"}
                        ]}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results.length()").value(3))
                .andExpect(jsonPath("$.results[0].status").value("RETRYABLE"))
                .andExpect(jsonPath("$.results[1].status").value("REJECTED_PERMANENT"))
                .andExpect(jsonPath("$.results[2].status").value("RETRYABLE"))
                .andExpect(jsonPath("$.retryableIds.length()").value(2))
                .andExpect(jsonPath("$.retryableIds[0]").value("c5b0ff1a-2c59-4333-ac72-1b41b84e84a1"))
                .andExpect(jsonPath("$.retryableIds[1]").value("f15ef836-80cd-42f7-a50e-a59e8762929d"))
                .andExpect(jsonPath("$.rejectedIds[0]").value("invalid-uuid"));
    }
}
