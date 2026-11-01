package vn.edu.hcmut.tmc.kiosk;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Without the {@code dev} profile the unauthenticated fixture endpoints must simply not exist. */
@SpringBootTest(properties = "kiosk.sync.enabled=false")
@AutoConfigureMockMvc
class KioskWithoutDevProfileTest {

    private static final Path DB_DIR;

    static {
        try {
            DB_DIR = Files.createTempDirectory("kiosk-nodev-test");
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("kiosk.sqlite.path", () -> DB_DIR.resolve("kiosk.db").toString());
    }

    @Autowired
    MockMvc mvc;

    @Test
    void devFixtureEndpointsAreNotMapped() throws Exception {
        mvc.perform(get("/api/dev/kiosk/queue")).andExpect(status().isNotFound());
        mvc.perform(post("/api/dev/kiosk/synthetic-event")).andExpect(status().isNotFound());
        mvc.perform(post("/api/dev/kiosk/sync/run")).andExpect(status().isNotFound());
    }

    @Test
    void healthIsStillUp() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
}
