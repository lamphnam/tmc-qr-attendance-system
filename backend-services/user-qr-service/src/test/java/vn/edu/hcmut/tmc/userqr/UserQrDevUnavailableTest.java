package vn.edu.hcmut.tmc.userqr;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:postgresql://127.0.0.1:1/unreachable",
        "spring.datasource.hikari.connection-timeout=250"})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class UserQrDevUnavailableTest {
    @Autowired MockMvc mvc;

    @Test
    void startsWithoutDatabaseButRealRoutesReportUnavailable() throws Exception {
        mvc.perform(get("/api/v1/qr/EMP-001")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("STORAGE_UNAVAILABLE"));
        for (String action : new String[]{"rotate", "revoke", "activate"}) {
            mvc.perform(post("/api/v1/qr/EMP-001/" + action)).andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("STORAGE_UNAVAILABLE"));
        }
        mvc.perform(get("/api/v1/kiosk/whitelist?factoryId=BD")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("KIOSK_AUTH_NOT_CONFIGURED"));
    }
}
