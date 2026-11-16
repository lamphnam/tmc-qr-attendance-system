package vn.edu.hcmut.tmc.userqr;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.edu.hcmut.tmc.userqr.application.service.UserQrApplicationService;
import vn.edu.hcmut.tmc.userqr.domain.exception.QrStorageException;
import vn.edu.hcmut.tmc.userqr.domain.model.AuditEvent;
import vn.edu.hcmut.tmc.userqr.domain.model.AuditLog;
import vn.edu.hcmut.tmc.userqr.domain.repository.*;
import vn.edu.hcmut.tmc.userqr.domain.service.AuditChecksumCalculator;

/** Real schema/seed and PostgreSQL 16. No duplicated DDL, no in-memory SQL emulation. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = "tmc.qr.kiosk-api-key=demo-test-key")
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class UserQrPostgresIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeAll
    static void loadCanonicalSchemaAndSeed() throws Exception {
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("../../init-db/schema.sql"));
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("../../init-db/seed.sql"));
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserQrApplicationService service;
    @Autowired IEmployeeQrRepository employees;
    @Autowired IAuditLogRepository audit;
    @Autowired IAuditLogQuery auditQuery;
    @Autowired AuditChecksumCalculator checksums;
    @Autowired TransactionTemplate transactions;

    private String employee() {
        String id = "T-" + UUID.randomUUID().toString().substring(0, 30);
        jdbc.update("""
                INSERT INTO employees (employee_id, full_name, factory_id, department, shared_secret)
                VALUES (?, 'Demo test', 'BD', 'Assembly', 'DUMMY_INTEGRATION_SECRET')
                """, id);
        return id;
    }

    @Test
    void seedsBothFactoriesFiveNewEmployeesAndKeepsLegacyFixtures() throws Exception {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM employees WHERE employee_id IN ('EMP-001','EMP-002','EMP-003','EMP-004','EMP-005')", Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM employees WHERE employee_id IN ('EMP001','EMP002','EMP003','EMP004')", Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForList("SELECT DISTINCT factory_id FROM employees WHERE employee_id LIKE 'EMP-%'", String.class))
                .containsExactlyInAnyOrder("BD", "LA");
        mvc.perform(get("/api/v1/qr/EMP-005")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/kiosk/whitelist?factoryId=LA").header("X-Kiosk-Api-Key", "demo-test-key"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].factoryId").value("LA"));
    }

    @Test
    void lifecycleCommitsEmployeeAndLinkedAuditRowsTogether() throws Exception {
        String id = employee();
        var before = employees.findById(id).orElseThrow();
        mvc.perform(get("/api/v1/qr/" + id)).andExpect(status().isOk()).andExpect(jsonPath("$.secretVersion").value(1));
        mvc.perform(post("/api/v1/qr/" + id + "/rotate")).andExpect(status().isOk()).andExpect(jsonPath("$.secretVersion").value(2));
        String rotated = employees.findById(id).orElseThrow().sharedSecret();
        assertThat(rotated).isNotEqualTo(before.sharedSecret());
        mvc.perform(post("/api/v1/qr/" + id + "/revoke")).andExpect(status().isOk()).andExpect(jsonPath("$.secretVersion").value(3));
        mvc.perform(get("/api/v1/qr/" + id)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/qr/" + id + "/activate")).andExpect(status().isOk()).andExpect(jsonPath("$.secretVersion").value(4));
        assertThat(employees.findById(id).orElseThrow().sharedSecret()).isNotEqualTo(rotated);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE target_resource = ?", Integer.class, id)).isEqualTo(3);
        assertThat(jdbc.queryForList("SELECT payload_summary FROM audit_logs WHERE target_resource = ?", String.class, id))
                .allSatisfy(summary -> assertThat(summary).doesNotContain(before.sharedSecret(), rotated));
        verifyChain();
    }

    @Test
    void failedAuditInsertRollsBackSecretAndVersionChange() {
        String id = employee();
        var before = employees.findById(id).orElseThrow();
        int logsBefore = jdbc.queryForObject("SELECT count(*) FROM audit_logs", Integer.class);
        // Violates the real VARCHAR(64) constraint after the employee UPDATE has executed.
        assertThatThrownBy(() -> service.rotateSecret(id, "x".repeat(65), "HR_STAFF", "test"))
                .isInstanceOf(QrStorageException.class);
        assertThat(employees.findById(id).orElseThrow()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs", Integer.class)).isEqualTo(logsBefore);
    }

    @Test
    void concurrentRotationsNeverLoseVersionUpdatesOrForkHashChain() throws Exception {
        String id = employee();
        runConcurrent(java.util.Collections.nCopies(8, id));
        assertThat(employees.findById(id).orElseThrow().secretVersion()).isEqualTo(9);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE target_resource = ?", Integer.class, id)).isEqualTo(8);
        verifyChain();
    }

    @Test
    void concurrentDifferentEmployeesStillProduceOneContinuousChain() throws Exception {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 8; i++) ids.add(employee());
        runConcurrent(ids);
        verifyChain();
    }

    @Test
    void appendContractRejectsDuplicateIdAndRequiresTransaction() {
        var event = new AuditEvent(UUID.randomUUID().toString(), java.time.Instant.now(), "demo", "HR_STAFF",
                "REVOKE_QR", employee(), "test", "SUCCESS");
        assertThatThrownBy(() -> audit.append(event)).isInstanceOf(IllegalStateException.class);
        transactions.execute(status -> audit.append(event));
        assertThatThrownBy(() -> transactions.execute(status -> audit.append(event))).isInstanceOf(QrStorageException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE log_id = ?", Integer.class, event.logId())).isEqualTo(1);
        assertThatThrownBy(() -> employees.findByIdForUpdate(event.targetResource())).isInstanceOf(IllegalStateException.class);
        verifyChain();
    }

    @Test
    void detectsRealRowTamperingThenRestoresTestFixture() {
        String id = employee();
        service.revokeQr(id, null, null, null);
        AuditLog log = auditQuery.findRecent(1).getFirst();
        try {
            jdbc.update("UPDATE audit_logs SET payload_summary = 'tampered' WHERE log_id = ?", log.event().logId());
            AuditLog changed = auditQuery.findRecent(1).getFirst();
            assertThat(checksums.calculate(changed.event(), changed.previousHash())).isNotEqualTo(changed.checksum());
        } finally {
            jdbc.update("UPDATE audit_logs SET payload_summary = ? WHERE log_id = ?", log.event().payloadSummary(), log.event().logId());
        }
        // This privileged test also demonstrates why a DB owner and no external anchor cannot claim immutability.
        verifyChain();
    }

    private void runConcurrent(List<String> ids) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(ids.size())) {
            List<Future<?>> tasks = new ArrayList<>();
            for (String id : ids) tasks.add(executor.submit(() -> {
                start.await();
                service.rotateSecret(id, "demo", "HR_STAFF", null);
                return null;
            }));
            start.countDown();
            for (Future<?> task : tasks) task.get(30, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    private void verifyChain() {
        List<AuditLog> oldestFirst = auditQuery.findRecent(200).reversed();
        String previous = AuditChecksumCalculator.GENESIS_HASH;
        for (AuditLog log : oldestFirst) {
            assertThat(log.previousHash()).isEqualTo(previous);
            assertThat(log.checksum()).isEqualTo(checksums.calculate(log.event(), previous));
            previous = log.checksum();
        }
    }
}
