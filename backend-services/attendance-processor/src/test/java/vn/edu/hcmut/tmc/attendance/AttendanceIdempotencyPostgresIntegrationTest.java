package vn.edu.hcmut.tmc.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import vn.edu.hcmut.tmc.attendance.application.service.AttendanceIngestionService;
import vn.edu.hcmut.tmc.attendance.application.service.IngestionResult;
import vn.edu.hcmut.tmc.attendance.application.service.IngestionResult.Status;
import vn.edu.hcmut.tmc.attendance.domain.model.AttendanceRecord;
import vn.edu.hcmut.tmc.attendance.domain.model.Direction;
import vn.edu.hcmut.tmc.attendance.domain.model.ScanResult;

/**
 * Real PostgreSQL, real {@code init-db/schema.sql} (single source of truth - no duplicate DDL here).
 * Skipped, not faked, when Docker is unavailable. Asserts on stored ROWS, not just HTTP statuses.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class AttendanceIdempotencyPostgresIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeAll
    static void applyCentralSchema() throws Exception {
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("../../init-db/schema.sql"));
        }
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    AttendanceIngestionService service;

    private static String eventJson(UUID id, String employeeId) {
        return """
                {"attendanceId":"%s","employeeId":"%s","factoryId":"BD","gateId":"BD-G01",
                 "direction":"IN","occurredAt":"2026-11-01T01:20:00.123Z","scanResult":"ALLOWED"}
                """.formatted(id, employeeId);
    }

    private int rowsFor(UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM attendance_events WHERE attendance_id = ?", Integer.class, id);
    }

    @Test
    void healthIsUpAgainstRealPostgres() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.components.db.status").value("UP"));
    }

    @Test
    void sendingTheSameEventTwiceCreatesExactlyOneRow() throws Exception {
        UUID id = UUID.randomUUID();

        mvc.perform(post("/api/dev/attendance/events").contentType(MediaType.APPLICATION_JSON).content(eventJson(id, "EMP001")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("STORED")).andExpect(jsonPath("$.duplicate").value(false));
        assertThat(rowsFor(id)).isEqualTo(1);

        mvc.perform(post("/api/dev/attendance/events").contentType(MediaType.APPLICATION_JSON).content(eventJson(id, "EMP001")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ALREADY_EXISTS")).andExpect(jsonPath("$.duplicate").value(true));
        assertThat(rowsFor(id)).isEqualTo(1);
    }

    @Test
    void storedRowRoundTripsAndIsFlaggedAsDevFixture() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(post("/api/dev/attendance/events").contentType(MediaType.APPLICATION_JSON).content(eventJson(id, "EMP002")))
                .andExpect(status().isOk());

        mvc.perform(get("/api/dev/attendance/events/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.employeeId").value("EMP002"))
                .andExpect(jsonPath("$.occurredAt").value("2026-11-01T01:20:00.123Z"));
        assertThat(jdbc.queryForObject("SELECT dev_fixture FROM attendance_events WHERE attendance_id = ?", Boolean.class, id)).isTrue();
    }

    @Test
    void sameIdWithDifferentPayloadIs409AndTheStoredRowIsUntouched() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(post("/api/dev/attendance/events").contentType(MediaType.APPLICATION_JSON).content(eventJson(id, "EMP001")))
                .andExpect(status().isOk());

        mvc.perform(post("/api/dev/attendance/events").contentType(MediaType.APPLICATION_JSON).content(eventJson(id, "EMP999")))
                .andExpect(status().isConflict());

        assertThat(jdbc.queryForObject("SELECT employee_id FROM attendance_events WHERE attendance_id = ?", String.class, id)).isEqualTo("EMP001");
        assertThat(rowsFor(id)).isEqualTo(1);
    }

    @Test
    void batchAcksEachEventAndRepeatingTheBatchAddsNoRows() throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        String batch = """
                {"kioskId":"BD-K01","events":[%s,%s]}
                """.formatted(eventJson(a, "EMP001"), eventJson(b, "EMP002"));

        mvc.perform(post("/api/dev/attendance/sync").contentType(MediaType.APPLICATION_JSON).content(batch))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].status").value("STORED"))
                .andExpect(jsonPath("$.results[1].status").value("STORED"));
        mvc.perform(post("/api/dev/attendance/sync").contentType(MediaType.APPLICATION_JSON).content(batch))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].status").value("ALREADY_EXISTS"))
                .andExpect(jsonPath("$.results[1].status").value("ALREADY_EXISTS"));

        assertThat(rowsFor(a) + rowsFor(b)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT source_kiosk_id FROM attendance_events WHERE attendance_id = ?", String.class, a)).isEqualTo("BD-K01");
    }

    @Test
    void concurrentRetriesOfOneIdCreateExactlyOneRow() throws Exception {
        UUID id = UUID.randomUUID();
        AttendanceRecord record = new AttendanceRecord(id, "EMP001", "BD", "BD-G01", Direction.IN,
                Instant.parse("2026-11-01T01:20:00Z"), ScanResult.ALLOWED, null, true);
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<IngestionResult>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return service.ingest(record);
                }));
            }
            start.countDown();
            List<Status> statuses = new ArrayList<>();
            for (Future<IngestionResult> f : futures) {
                statuses.add(f.get().status());
            }

            assertThat(statuses).filteredOn(s -> s == Status.STORED).hasSize(1);
            assertThat(statuses).filteredOn(s -> s == Status.ALREADY_EXISTS).hasSize(threads - 1);
            assertThat(rowsFor(id)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
