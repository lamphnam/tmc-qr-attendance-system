package vn.edu.hcmut.tmc.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Boots the full context against an unreachable database: the service must start, /actuator/health
 * must say DOWN (readiness is real, not hard-coded), and every contract stub must answer an honest 501.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:postgresql://127.0.0.1:1/unreachable",
        "spring.datasource.hikari.connection-timeout=250"
})
@AutoConfigureMockMvc
class ReportingServiceApplicationTest {

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
    void everyContractStubAnswersNotImplemented() throws Exception {
        String[][] routes = {
            {"GET", "/api/v1/reports/attendance"},
            {"GET", "/api/v1/reports/dashboard"},
            {"POST", "/api/v1/reports/hr-export/trigger"}
        };
        for (String[] route : routes) {
            mvc.perform(request(HttpMethod.valueOf(route[0]), route[1]))
                    .andExpect(status().isNotImplemented())
                    .andExpect(jsonPath("$.code").value("NOT_IMPLEMENTED"))
                    .andExpect(jsonPath("$.correlationId").isNotEmpty());
        }
    }
}
