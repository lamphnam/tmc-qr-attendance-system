package vn.edu.hcmut.tmc.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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

import vn.edu.hcmut.tmc.reporting.application.service.HrExportResult;
import vn.edu.hcmut.tmc.reporting.application.service.HrExportResult.Outcome;
import vn.edu.hcmut.tmc.reporting.application.service.HrExportService;
import vn.edu.hcmut.tmc.reporting.domain.model.ExportPeriod;

/**
 * Real PostgreSQL 16 with the canonical {@code init-db/schema.sql} + {@code seed.sql} (no duplicate DDL), the real HTTP
 * layer and the {@code file} payroll channel writing into a temporary drop directory. Skipped, not faked, without
 * Docker. Assertions are on rows and files, not only status codes.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "tmc.hr-export.channel=file",
        "tmc.hr-export.scheduler-enabled=false",
        "tmc.reporting.include-dev-fixtures=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class ReportingPostgresIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @TempDir
    static Path drop;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("tmc.hr-export.file.directory", () -> drop.toString());
    }

    @BeforeAll
    static void applyCentralSchemaAndSeed() throws Exception {
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("../../init-db/schema.sql"));
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("../../init-db/seed.sql"));
        }
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    HrExportService exportService;

    private UUID event(String employeeId, String direction, String occurredAt, String scanResult, boolean devFixture) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO attendance_events
                    (attendance_id, employee_id, factory_id, gate_id, direction, occurred_at, scan_result, dev_fixture)
                VALUES (?, ?, 'BD', 'BD-G01', ?, ?, ?, ?)
                """, id, employeeId, direction, OffsetDateTime.parse(occurredAt), scanResult, devFixture);
        return id;
    }

    private UUID accepted(String employeeId, String direction, String occurredAt) {
        return event(employeeId, direction, occurredAt, "ALLOWED", false);
    }

    private Set<UUID> frozenItems(String period) {
        return new HashSet<>(jdbc.queryForList("""
                SELECT i.attendance_id FROM hr_export_batch_items i
                JOIN hr_export_batches b ON b.batch_uuid = i.batch_uuid WHERE b.period = ?
                """, UUID.class, period));
    }

    private List<Path> dropFiles(String period) throws IOException {
        try (Stream<Path> files = Files.list(drop)) {
            return files.filter(f -> f.getFileName().toString().startsWith("hr-export_" + period + "_")).toList();
        }
    }

    private static String trigger(String period) {
        return "{\"period\":\"" + period + "\"}";
    }

    @Test
    void healthIsUpAgainstRealPostgres() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.components.db.status").value("UP"));
    }

    @Test
    void anExportFreezesOnlyAcceptedRealEventsOfTheVietnameseMonth() throws Exception {
        UUID firstMinute = accepted("EMP001", "IN", "2025-02-28T17:30:00Z");   // 1 Mar 00:30 VN  -> in
        UUID lastSecond = accepted("EMP002", "OUT", "2025-03-31T16:59:59Z");   // 31 Mar 23:59 VN -> in
        accepted("EMP001", "IN", "2025-03-31T17:00:00Z");                      // 1 Apr 00:00 VN  -> out
        accepted("EMP001", "OUT", "2025-02-28T16:59:59Z");                     // 28 Feb 23:59 VN -> out
        event("EMP002", "IN", "2025-03-15T03:00:00Z", "REJECTED", false);      // rejected scan    -> out
        event("EMP002", "IN", "2025-03-15T04:00:00Z", "ALLOWED", true);        // dev fixture      -> out
        UUID expectedBatch = ExportPeriod.parse("2025-03").batchUuid();

        mvc.perform(post("/api/dev/reports/hr-export/trigger").contentType(MediaType.APPLICATION_JSON).content(trigger("2025-03")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("DELIVERED"))
                .andExpect(jsonPath("$.duplicateBatch").value(false))
                .andExpect(jsonPath("$.batch.batchUuid").value(expectedBatch.toString()))
                .andExpect(jsonPath("$.batch.status").value("SENT"))
                .andExpect(jsonPath("$.batch.triggerSource").value("MANUAL"))
                .andExpect(jsonPath("$.batch.itemCount").value(2));

        assertThat(frozenItems("2025-03")).containsExactlyInAnyOrder(firstMinute, lastSecond);
        List<Path> files = dropFiles("2025-03");
        assertThat(files).singleElement().satisfies(file -> {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            assertThat(lines).hasSize(3);
            assertThat(String.join("\n", lines)).contains(firstMinute.toString()).contains(lastSecond.toString());
        });
    }

    @Test
    void aSecondTriggerIsRefusedAndALateEventDoesNotChangeTheFrozenBatch() throws Exception {
        accepted("EMP001", "IN", "2025-08-05T01:00:00Z");
        mvc.perform(post("/api/dev/reports/hr-export/trigger").contentType(MediaType.APPLICATION_JSON).content(trigger("2025-08")))
                .andExpect(status().isOk());
        accepted("EMP002", "IN", "2025-08-20T01:00:00Z"); // arrives after the export (a kiosk that was offline)

        mvc.perform(post("/api/dev/reports/hr-export/trigger").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Request-Id", "req-409").content(trigger("2025-08")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_EXPORTED"))
                .andExpect(jsonPath("$.correlationId").value("req-409"));

        mvc.perform(get("/api/dev/reports/hr-export/batches/2025-08"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemCount").value(1))
                .andExpect(jsonPath("$.attemptCount").value(1))
                .andExpect(jsonPath("$.status").value("SENT"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM hr_export_batches WHERE period = '2025-08'", Integer.class)).isEqualTo(1);
        assertThat(dropFiles("2025-08")).hasSize(1);
    }

    @Test
    void concurrentTriggersForOneMonthCreateOneBatchAndSendItOnce() throws Exception {
        accepted("EMP001", "IN", "2025-05-05T01:00:00Z");
        accepted("EMP002", "IN", "2025-05-06T01:00:00Z");
        ExportPeriod may = ExportPeriod.parse("2025-05");
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<HrExportResult>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return exportService.triggerManually(may);
                }));
            }
            start.countDown();
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<HrExportResult> future : futures) {
                outcomes.add(future.get().outcome());
            }

            assertThat(outcomes).filteredOn(o -> o == Outcome.DELIVERED).hasSize(1);
            assertThat(outcomes).allMatch(o -> o == Outcome.DELIVERED || o == Outcome.ALREADY_EXPORTED || o == Outcome.IN_PROGRESS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM hr_export_batches WHERE period = '2025-05'", Integer.class)).isEqualTo(1);
        assertThat(frozenItems("2025-05")).hasSize(2);
        assertThat(dropFiles("2025-05")).hasSize(1);
    }

    @Test
    void aMonthThatIsNotOverCannotBeExportedAndAnUnknownBatchIs404() throws Exception {
        mvc.perform(post("/api/dev/reports/hr-export/trigger").contentType(MediaType.APPLICATION_JSON).content(trigger("2999-12")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM hr_export_batches WHERE period = '2999-12'", Integer.class)).isZero();

        mvc.perform(get("/api/dev/reports/hr-export/batches/2001-01"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void theReportGroupsByVietnameseDayJoinsDepartmentsAndCountsOnlyAcceptedRealScans() throws Exception {
        juneTenth();

        mvc.perform(get("/api/dev/reports/attendance").param("factoryId", "BD").param("from", "2025-06-10").param("to", "2025-06-10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timeZone").value("Asia/Ho_Chi_Minh"))
                .andExpect(jsonPath("$.rows.length()").value(3))
                .andExpect(jsonPath("$.rows[0].employeeId").value("EMP001"))
                .andExpect(jsonPath("$.rows[0].department").value("Assembly"))
                .andExpect(jsonPath("$.rows[0].firstIn").value("2025-06-10T01:00:00Z"))
                .andExpect(jsonPath("$.rows[0].lastOut").value("2025-06-10T10:00:00Z"))
                .andExpect(jsonPath("$.rows[1].employeeId").value("EMP002"))
                .andExpect(jsonPath("$.rows[1].lastOut").doesNotExist())
                .andExpect(jsonPath("$.rows[2].employeeId").value("EMPX"))
                .andExpect(jsonPath("$.rows[2].fullName").doesNotExist())
                .andExpect(jsonPath("$.rows[2].firstIn").value("2025-06-09T17:30:00Z")) // 00:30 on 10 June in Vietnam
                .andExpect(jsonPath("$.rows[2].inCount").value(2))
                .andExpect(jsonPath("$.departments.length()").value(3))
                .andExpect(jsonPath("$.departments[0].department").value("Assembly"))
                .andExpect(jsonPath("$.departments[0].scans").value(2))
                .andExpect(jsonPath("$.departments[2].department").doesNotExist())
                .andExpect(jsonPath("$.hasMore").value(false));

        mvc.perform(get("/api/dev/reports/attendance").param("factoryId", "BD").param("from", "2025-06-10").param("to", "2025-06-10")
                        .param("department", "Assembly"))
                .andExpect(jsonPath("$.rows.length()").value(1));
        mvc.perform(get("/api/dev/reports/attendance").param("factoryId", "BD").param("from", "2025-06-10").param("to", "2025-06-10")
                        .param("limit", "2"))
                .andExpect(jsonPath("$.rows.length()").value(2))
                .andExpect(jsonPath("$.hasMore").value(true));
        mvc.perform(get("/api/dev/reports/attendance").param("factoryId", "BD").param("from", "2025-06-10").param("to", "2025-06-11"))
                .andExpect(jsonPath("$.rows.length()").value(4))
                .andExpect(jsonPath("$.rows[3].workDate").value("2025-06-11"));
    }

    @Test
    void theDashboardCountsOneFactoryDayAndShowsTheLatestExport() throws Exception {
        juneTenth();

        mvc.perform(get("/api/dev/reports/dashboard").param("factoryId", "BD").param("date", "2025-06-10"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-TMC-Dev-Only", "true"))
                .andExpect(jsonPath("$.employeesPresent").value(3))
                .andExpect(jsonPath("$.scansIn").value(4))
                .andExpect(jsonPath("$.scansOut").value(1))
                .andExpect(jsonPath("$.rejectedScans").value(1))
                .andExpect(jsonPath("$.stillInside").value(2))
                .andExpect(jsonPath("$.lastScanAt").value("2025-06-10T10:00:00Z"))
                .andExpect(jsonPath("$.presentByDepartment.length()").value(3));
    }

    @Test
    void aReportLongerThan31DaysIsRefusedBeforeTouchingTheDatabase() throws Exception {
        mvc.perform(get("/api/dev/reports/attendance").param("factoryId", "BD").param("from", "2025-01-01").param("to", "2025-02-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    /** One factory day (10 June 2025, Vietnam), inserted once per JVM run; the day is used by no other test. */
    private void juneTenth() {
        Integer existing = jdbc.queryForObject(
                "SELECT count(*) FROM attendance_events WHERE employee_id = 'EMPX'", Integer.class);
        if (existing != null && existing > 0) {
            return;
        }
        accepted("EMP001", "IN", "2025-06-10T01:00:00Z");                  // 08:00 VN
        accepted("EMP001", "OUT", "2025-06-10T10:00:00Z");                 // 17:00 VN
        accepted("EMP002", "IN", "2025-06-10T01:05:00Z");
        accepted("EMPX", "IN", "2025-06-09T17:30:00Z");                    // 10 June 00:30 VN, unknown employee
        accepted("EMPX", "IN", "2025-06-10T02:00:00Z");
        event("EMP004", "IN", "2025-06-10T03:00:00Z", "REJECTED", false);  // rejected: not attendance
        event("EMP004", "IN", "2025-06-10T04:00:00Z", "ALLOWED", true);    // dev fixture: excluded
        accepted("EMP002", "IN", "2025-06-10T17:30:00Z");                  // 11 June 00:30 VN
    }
}
