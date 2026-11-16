package vn.edu.hcmut.tmc.userqr;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.edu.hcmut.tmc.userqr.application.service.UserQrApplicationService;
import vn.edu.hcmut.tmc.userqr.domain.exception.QrStorageException;
import vn.edu.hcmut.tmc.userqr.domain.model.AuditEvent;
import vn.edu.hcmut.tmc.userqr.domain.model.AuditLog;
import vn.edu.hcmut.tmc.userqr.domain.model.EmployeeQr;
import vn.edu.hcmut.tmc.userqr.domain.model.QrStatus;
import vn.edu.hcmut.tmc.userqr.domain.exception.FactoryNotFoundException;
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
            try (var statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT whitelist_version FROM factories ORDER BY factory_id")) {
                while (rows.next()) assertThat(rows.getLong(1)).isEqualTo(1);
            }
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserQrApplicationService service;
    @MockitoSpyBean IEmployeeQrRepository employees;
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
        mvc.perform(get("/api/v1/qr/EMP-005")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QR_REVOKED"));
        mvc.perform(get("/api/v1/kiosk/whitelist?factoryId=LA").header("X-Kiosk-Api-Key", "demo-test-key"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].factoryId").value("LA"));
    }

    @Test
    void lifecycleCommitsEmployeeAndLinkedAuditRowsTogether() throws Exception {
        String id = employee();
        var before = employees.findById(id).orElseThrow();
        long revision = revision("BD");
        mvc.perform(get("/api/v1/qr/" + id)).andExpect(status().isOk()).andExpect(jsonPath("$.secretVersion").value(1));
        mvc.perform(post("/api/v1/qr/" + id + "/rotate")).andExpect(status().isOk()).andExpect(jsonPath("$.secretVersion").value(2));
        assertSnapshot("BD", ++revision, id, 2, "ACTIVE");
        String rotated = employees.findById(id).orElseThrow().sharedSecret();
        assertThat(rotated).isNotEqualTo(before.sharedSecret());
        mvc.perform(post("/api/v1/qr/" + id + "/revoke")).andExpect(status().isOk()).andExpect(jsonPath("$.secretVersion").value(3));
        assertSnapshot("BD", ++revision, id, 3, "REVOKED");
        mvc.perform(get("/api/v1/qr/" + id)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QR_REVOKED"));
        mvc.perform(post("/api/v1/qr/" + id + "/activate")).andExpect(status().isOk()).andExpect(jsonPath("$.secretVersion").value(4));
        assertSnapshot("BD", ++revision, id, 4, "ACTIVE");
        assertThat(employees.findById(id).orElseThrow().sharedSecret()).isNotEqualTo(rotated);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE target_resource = ?", Integer.class, id)).isEqualTo(3);
        assertThat(jdbc.queryForList("SELECT payload_summary FROM audit_logs WHERE target_resource = ?", String.class, id))
                .allSatisfy(summary -> assertThat(summary).doesNotContain(before.sharedSecret(), rotated));
        verifyChain();
    }

    @ParameterizedTest
    @ValueSource(strings = {"rotate", "revoke", "activate"})
    void failedAuditInsertRollsBackSecretAndVersionChange(String action) {
        String id = employee();
        var before = employees.findById(id).orElseThrow();
        int logsBefore = jdbc.queryForObject("SELECT count(*) FROM audit_logs", Integer.class);
        long revision = revision("BD");
        // Violates the real VARCHAR(64) constraint after the employee UPDATE has executed.
        assertThatThrownBy(() -> {
            switch (action) {
                case "rotate" -> service.rotateSecret(id, "x".repeat(65), "HR_STAFF", "test");
                case "revoke" -> service.revokeQr(id, "x".repeat(65), "HR_STAFF", "test");
                case "activate" -> service.activateQr(id, "x".repeat(65), "HR_STAFF", "test");
                default -> throw new IllegalArgumentException("Unknown test action");
            }
        })
                .isInstanceOf(QrStorageException.class);
        assertThat(employees.findById(id).orElseThrow()).isEqualTo(before);
        assertThat(revision("BD")).isEqualTo(revision);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs", Integer.class)).isEqualTo(logsBefore);
    }

    @Test
    void concurrentRotationsNeverLoseVersionUpdatesOrForkHashChain() throws Exception {
        String id = employee();
        long revision = revision("BD");
        runConcurrent(java.util.Collections.nCopies(8, id));
        assertThat(revision("BD")).isEqualTo(revision + 8);
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
        assertThatThrownBy(() -> employees.insert(newEmployee("BD"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> employees.delete(event.targetResource())).isInstanceOf(IllegalStateException.class);
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

    @Test
    void qrPayloadMatchesLegacyFieldsOnIssueRotateAndActivate() throws Exception {
        String id = employee();
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        for (String action : new String[]{"", "/rotate", "/activate"}) {
            var response = mvc.perform(action.isEmpty() ? get("/api/v1/qr/" + id) : post("/api/v1/qr/" + id + action))
                    .andExpect(status().isOk()).andReturn().getResponse();
            var body = mapper.readTree(response.getContentAsString());
            assertThat(body.get("periodSeconds").asInt()).isEqualTo(30);
            var payload = body.get("qrPayload");
            assertThat(payload.size()).isEqualTo(4);
            assertThat(payload.get("empId").asText()).isEqualTo(id);
            assertThat(payload.get("totp").asText()).matches("[0-9]{6}").isEqualTo(body.get("totpCode").asText());
            assertThat(payload.get("ts").asLong()).isEqualTo(java.time.Instant.parse(body.get("issuedAt").asText()).getEpochSecond());
            assertThat(payload.get("ver").asInt()).isEqualTo(body.get("secretVersion").asInt());
            assertThat(body.has("sharedSecret")).isFalse();
        }
    }

    @Test
    void emptySnapshotIsVersionedAndFactoryIsRequiredAndMustExist() throws Exception {
        String factory = factory();
        assertEmptySnapshot(factory, 1);
        for (String query : new String[]{"", "?factoryId=", "?factoryId=bad!"}) {
            mvc.perform(get("/api/v1/kiosk/whitelist" + query).header("X-Kiosk-Api-Key", "demo-test-key"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
        mvc.perform(get("/api/v1/kiosk/whitelist?factoryId=NO_SUCH_FACTORY").header("X-Kiosk-Api-Key", "demo-test-key"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void membershipAndFactoryTransferBumpBothScopesAndKeepEmptyRevision() throws Exception {
        String oldFactory = factory();
        String newFactory = factory();
        EmployeeQr row = newEmployee(oldFactory);
        service.addEmployee(row, null, null, null);
        assertSnapshot(oldFactory, 2, row.employeeId(), 1, "ACTIVE");
        service.transferEmployee(row.employeeId(), newFactory, null, null, null);
        assertEmptySnapshot(oldFactory, 3);
        assertSnapshot(newFactory, 2, row.employeeId(), 1, "ACTIVE");
        service.transferEmployee(row.employeeId(), newFactory, null, null, null);
        assertThat(revision(newFactory)).isEqualTo(2);
        service.deleteEmployee(row.employeeId(), null, null, null);
        assertEmptySnapshot(newFactory, 3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE target_resource = ?", Integer.class, row.employeeId())).isEqualTo(3);
        verifyChain();
    }

    @Test
    void failedMembershipAuditRollsBackInsertTransferDeleteAndBothRevisions() {
        String oldFactory = factory();
        String newFactory = factory();
        EmployeeQr row = newEmployee(oldFactory);
        String badActor = "x".repeat(65);
        assertThatThrownBy(() -> service.addEmployee(row, badActor, null, null)).isInstanceOf(QrStorageException.class);
        assertThat(employees.findById(row.employeeId())).isEmpty();
        assertThat(revision(oldFactory)).isEqualTo(1);
        service.addEmployee(row, null, null, null);
        assertThatThrownBy(() -> service.transferEmployee(row.employeeId(), newFactory, badActor, null, null)).isInstanceOf(QrStorageException.class);
        assertThat(employees.findById(row.employeeId()).orElseThrow()).isEqualTo(row);
        assertThat(revision(oldFactory)).isEqualTo(2);
        assertThat(revision(newFactory)).isEqualTo(1);
        assertThatThrownBy(() -> service.deleteEmployee(row.employeeId(), badActor, null, null)).isInstanceOf(QrStorageException.class);
        assertThat(employees.findById(row.employeeId()).orElseThrow()).isEqualTo(row);
        assertThat(revision(oldFactory)).isEqualTo(2);
        assertThatThrownBy(() -> service.transferEmployee(row.employeeId(), "NO_SUCH_FACTORY", null, null, null))
                .isInstanceOf(FactoryNotFoundException.class);
        assertThat(revision(oldFactory)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE target_resource = ?", Integer.class, row.employeeId())).isEqualTo(1);
        verifyChain();
    }

    @Test
    void oppositeTransfersCompleteWithoutDeadlockAndIncrementBothFactories() throws Exception {
        String a = factory();
        String b = factory();
        EmployeeQr first = newEmployee(a);
        EmployeeQr second = newEmployee(b);
        service.addEmployee(first, null, null, null);
        service.addEmployee(second, null, null, null);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var left = executor.submit(() -> { start.await(); return service.transferEmployee(first.employeeId(), b, null, null, null); });
            var right = executor.submit(() -> { start.await(); return service.transferEmployee(second.employeeId(), a, null, null, null); });
            start.countDown();
            assertThat(left.get(10, java.util.concurrent.TimeUnit.SECONDS).factoryId()).isEqualTo(b);
            assertThat(right.get(10, java.util.concurrent.TimeUnit.SECONDS).factoryId()).isEqualTo(a);
        }
        assertThat(revision(a)).isEqualTo(4);
        assertThat(revision(b)).isEqualTo(4);
        verifyChain();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void snapshotKeepsOldRevisionAndBodyWhenWriterCommitsBetweenSelects(boolean initiallyEmpty) throws Exception {
        String factory = factory();
        EmployeeQr row = newEmployee(factory);
        if (!initiallyEmpty) service.addEmployee(row, null, null, null);
        CountDownLatch versionRead = new CountDownLatch(1);
        CountDownLatch writerCommitted = new CountDownLatch(1);
        doAnswer(call -> {
            jdbc.execute((ConnectionCallback<Void>) connection -> {
                assertThat(connection.getTransactionIsolation()).isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
                assertThat(connection.isReadOnly()).isTrue();
                return null;
            });
            versionRead.countDown();
            assertThat(writerCommitted.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            return call.callRealMethod();
        }).when(employees).findWhitelist(factory);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var snapshot = executor.submit(() -> service.exportWhitelist(factory));
            try {
                assertThat(versionRead.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                if (initiallyEmpty) service.addEmployee(row, null, null, null);
                else service.rotateSecret(row.employeeId(), null, null, null);
            } finally {
                writerCommitted.countDown();
            }
            var old = snapshot.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(old.version()).isEqualTo(initiallyEmpty ? 1 : 2);
            if (initiallyEmpty) assertThat(old.employees()).isEmpty();
            else assertThat(old.employees()).containsExactly(row);
        } finally {
            doCallRealMethod().when(employees).findWhitelist(factory);
        }
        assertSnapshot(factory, initiallyEmpty ? 2 : 3, row.employeeId(), initiallyEmpty ? 1 : 2, "ACTIVE");
    }

    private String factory() {
        String id = "F-" + UUID.randomUUID().toString().substring(0, 10);
        jdbc.update("INSERT INTO factories (factory_id, name) VALUES (?, 'Synthetic test factory')", id);
        return id;
    }

    private EmployeeQr newEmployee(String factory) {
        return new EmployeeQr("T-" + UUID.randomUUID().toString().substring(0, 30), "Demo test", factory,
                "Assembly", "EMPLOYEE", "DUMMY_INTEGRATION_SECRET", QrStatus.ACTIVE, 1,
                java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
    }

    private long revision(String factory) {
        return jdbc.queryForObject("SELECT whitelist_version FROM factories WHERE factory_id = ?", Long.class, factory);
    }

    private void assertEmptySnapshot(String factory, long version) throws Exception {
        mvc.perform(get("/api/v1/kiosk/whitelist").param("factoryId", factory).header("X-Kiosk-Api-Key", "demo-test-key"))
                .andExpect(status().isOk()).andExpect(header().string("X-Whitelist-Version", Long.toString(version)))
                .andExpect(content().json("[]"));
    }

    private void assertSnapshot(String factory, long version, String employeeId, int secretVersion, String status) throws Exception {
        mvc.perform(get("/api/v1/kiosk/whitelist").param("factoryId", factory).header("X-Kiosk-Api-Key", "demo-test-key"))
                .andExpect(status().isOk()).andExpect(header().string("X-Whitelist-Version", Long.toString(version)))
                .andExpect(jsonPath("$[?(@.employeeId == '" + employeeId + "')].secretVersion").value(org.hamcrest.Matchers.contains(secretVersion)))
                .andExpect(jsonPath("$[?(@.employeeId == '" + employeeId + "')].qrStatus").value(org.hamcrest.Matchers.contains(status)));
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
