package vn.edu.hcmut.tmc.reporting;

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
 * Dev profile against an unreachable database: the error contract of the dev routes. Input errors are 400 before any SQL;
 * a database outage is an honest 503, never a 500 or an empty report; production routes stay 501.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:postgresql://127.0.0.1:1/unreachable",
        "spring.datasource.hikari.connection-timeout=250",
        "tmc.hr-export.scheduler-enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class ReportingDevProfileTest {

    @Autowired
    MockMvc mvc;

    @Test
    void aDatabaseOutageIs503WithTheCallersCorrelationId() throws Exception {
        mvc.perform(get("/api/dev/reports/dashboard").param("factoryId", "BD").header("X-Request-Id", "req-503"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("STORAGE_UNAVAILABLE"))
                .andExpect(jsonPath("$.correlationId").value("req-503"));
        mvc.perform(post("/api/dev/reports/hr-export/trigger").contentType(MediaType.APPLICATION_JSON).content("{\"period\":\"2025-01\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("STORAGE_UNAVAILABLE"));
    }

    @Test
    void missingOrMalformedParametersAre400AndNeverEchoTheValue() throws Exception {
        mvc.perform(get("/api/dev/reports/attendance").param("from", "2025-01-01").param("to", "2025-01-02"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("missing parameter: factoryId"));
        mvc.perform(get("/api/dev/reports/attendance").param("factoryId", "BD").param("from", "<script>").param("to", "2025-01-02"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("invalid value for parameter: from"));
        mvc.perform(get("/api/dev/reports/attendance").param("factoryId", "BD").param("from", "2025-01-01").param("to", "2025-03-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("a report covers at most 31 days"));
    }

    @Test
    void invalidExportRequestsAre400() throws Exception {
        mvc.perform(post("/api/dev/reports/hr-export/trigger").contentType(MediaType.APPLICATION_JSON).content("{\"period\":\"2025/01\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(post("/api/dev/reports/hr-export/trigger").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        mvc.perform(post("/api/dev/reports/hr-export/trigger").contentType(MediaType.APPLICATION_JSON).content("{\"period\":\"2999-01\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("period 2999-01 is not over yet in factory time"));
    }

    @Test
    void productionRoutesStay501EvenWithTheDevProfile() throws Exception {
        mvc.perform(get("/api/v1/reports/dashboard"))
                .andExpect(status().isNotImplemented())
                .andExpect(jsonPath("$.code").value("NOT_IMPLEMENTED"));
    }
}
