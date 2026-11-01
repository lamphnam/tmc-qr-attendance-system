package vn.edu.hcmut.tmc.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

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

    @Test
    void healthEndpointReportsDatabaseDownInsteadOfLying() throws Exception {
        String body = mvc.perform(get("/actuator/health"))
                .andExpect(status().isServiceUnavailable())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("\"status\":\"DOWN\"").contains("\"db\":{\"status\":\"DOWN\"");
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
}
