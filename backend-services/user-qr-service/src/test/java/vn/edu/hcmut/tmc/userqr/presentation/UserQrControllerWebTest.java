package vn.edu.hcmut.tmc.userqr.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import vn.edu.hcmut.tmc.userqr.application.service.*;
import vn.edu.hcmut.tmc.userqr.domain.exception.*;
import vn.edu.hcmut.tmc.userqr.domain.model.*;
import vn.edu.hcmut.tmc.userqr.presentation.controller.*;
import vn.edu.hcmut.tmc.userqr.presentation.dto.*;

@WebMvcTest(controllers = {UserQrController.class, KioskWhitelistController.class, QrGenerateContractController.class},
        properties = "tmc.qr.kiosk-api-key=demo-test-key")
@ActiveProfiles("dev")
class UserQrControllerWebTest {
    @Autowired MockMvc mvc;
    @MockitoBean UserQrApplicationService service;
    private static final Instant NOW = Instant.parse("2026-11-14T00:00:00Z");
    private static final EmployeeQr EMPLOYEE = new EmployeeQr("EMP-001", "Demo", "BD", "Assembly", "EMPLOYEE",
            "DUMMY_SENSITIVE_KEY", QrStatus.ACTIVE, 1, NOW);
    private static final QrIssue QR = new QrIssue("EMP-001", "Demo", "BD", QrStatus.ACTIVE, "048291",
            NOW.getEpochSecond() / 30, NOW, NOW.plusSeconds(30), 30, 1, "EMP-001:048291:1");

    @Test
    void qrShapeHasTimingAndPayloadButNeverSharedSecret() throws Exception {
        when(service.issueQr("EMP-001")).thenReturn(QR);
        mvc.perform(get("/api/v1/qr/EMP-001")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-TMC-Dev-Only", "true"))
                .andExpect(jsonPath("$.employeeId").value("EMP-001"))
                .andExpect(jsonPath("$.fullName").value("Demo"))
                .andExpect(jsonPath("$.factoryId").value("BD"))
                .andExpect(jsonPath("$.qrStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.totpCode").value("048291"))
                .andExpect(jsonPath("$.timeStep").value(QR.timeStep()))
                .andExpect(jsonPath("$.issuedAt").value(NOW.toString()))
                .andExpect(jsonPath("$.expiresAt").value(NOW.plusSeconds(30).toString()))
                .andExpect(jsonPath("$.remainingSeconds").value(30))
                .andExpect(jsonPath("$.secretVersion").value(1))
                .andExpect(jsonPath("$.qrTokenPayload").value("EMP-001:048291:1"))
                .andExpect(jsonPath("$.sharedSecret").doesNotExist());
    }

    @Test
    void lifecycleRoutesAcceptOptionalBodiesAndReturnSafeResponses() throws Exception {
        when(service.rotateSecret("EMP-001", null, null, null)).thenReturn(QR);
        when(service.activateQr("EMP-001", null, null, null)).thenReturn(QR);
        for (String action : new String[]{"rotate", "activate"}) {
            mvc.perform(post("/api/v1/qr/EMP-001/" + action)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.qrTokenPayload").isString())
                    .andExpect(jsonPath("$.sharedSecret").doesNotExist());
        }
        when(service.revokeQr("EMP-001", "hr-demo", "HR_STAFF", "left"))
                .thenReturn(EMPLOYEE.changeQr(EMPLOYEE.sharedSecret(), QrStatus.REVOKED, NOW));
        mvc.perform(post("/api/v1/qr/EMP-001/revoke").contentType(MediaType.APPLICATION_JSON)
                .content("{\"actorId\":\"hr-demo\",\"actorRole\":\"HR_STAFF\",\"reason\":\"left\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.qrStatus").value("REVOKED"))
                .andExpect(jsonPath("$.secretVersion").value(2))
                .andExpect(jsonPath("$.updatedAt").isString())
                .andExpect(jsonPath("$.sharedSecret").doesNotExist()).andExpect(jsonPath("$.totpCode").doesNotExist());
    }

    @Test
    void mapsRevokedMissingAndStorageErrorsWithoutEchoingInputs() throws Exception {
        when(service.issueQr("EMP-001")).thenThrow(new QrRevokedException());
        mvc.perform(get("/api/v1/qr/EMP-001").header("X-Request-Id", "test-correlation"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.correlationId").value("test-correlation"));
        when(service.issueQr("missing")).thenThrow(new EmployeeNotFoundException());
        mvc.perform(get("/api/v1/qr/missing")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        for (String action : new String[]{"rotate", "revoke", "activate"}) {
            switch (action) {
                case "rotate" -> when(service.rotateSecret("missing", null, null, null)).thenThrow(new EmployeeNotFoundException());
                case "revoke" -> when(service.revokeQr("missing", null, null, null)).thenThrow(new EmployeeNotFoundException());
                case "activate" -> when(service.activateQr("missing", null, null, null)).thenThrow(new EmployeeNotFoundException());
            }
            mvc.perform(post("/api/v1/qr/missing/" + action)).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").isString()).andExpect(jsonPath("$.correlationId").isNotEmpty());
        }
        when(service.issueQr("offline")).thenThrow(new QrStorageException(new RuntimeException("SENSITIVE_SQL")));
        String body = mvc.perform(get("/api/v1/qr/offline")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("STORAGE_UNAVAILABLE")).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("SENSITIVE_SQL", "stackTrace");
    }

    @Test
    void whitelistRequiresKioskCredentialAndPreservesScopeAndRevocations() throws Exception {
        mvc.perform(get("/api/v1/kiosk/whitelist")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/kiosk/whitelist").header("X-Kiosk-Api-Key", "wrong"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        verifyNoInteractions(service);
        when(service.exportWhitelist("BD")).thenReturn(List.of(EMPLOYEE,
                EMPLOYEE.changeQr(EMPLOYEE.sharedSecret(), QrStatus.REVOKED, NOW)));
        mvc.perform(get("/api/v1/kiosk/whitelist?factoryId=BD").header("X-Kiosk-Api-Key", "demo-test-key"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$[0].employeeId").value("EMP-001"))
                .andExpect(jsonPath("$[0].fullName").value("Demo"))
                .andExpect(jsonPath("$[0].department").value("Assembly"))
                .andExpect(jsonPath("$[0].factoryId").value("BD"))
                .andExpect(jsonPath("$[0].sharedSecret").value(EMPLOYEE.sharedSecret()))
                .andExpect(jsonPath("$[0].updatedAt").isString())
                .andExpect(jsonPath("$[1].qrStatus").value("REVOKED"))
                .andExpect(jsonPath("$[1].secretVersion").value(2));
        when(service.exportWhitelist(null)).thenReturn(List.of());
        mvc.perform(get("/api/v1/kiosk/whitelist").header("X-Kiosk-Api-Key", "demo-test-key"))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
    }

    @Test
    void auditViewerReturnsChainMetadataAndUsesBoundedLimit() throws Exception {
        var event = new AuditEvent("id", NOW, "demo", "HR_STAFF", "REVOKE_QR", "EMP-001", "version=2", "SUCCESS");
        when(service.auditLogs(100)).thenReturn(List.of(new AuditLog(1, event, "0".repeat(64), "a".repeat(64))));
        mvc.perform(get("/api/v1/qr/audit-logs")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].logId").value("id"))
                .andExpect(jsonPath("$[0].sequence").value(1))
                .andExpect(jsonPath("$[0].timestamp").isString())
                .andExpect(jsonPath("$[0].actorId").value("demo"))
                .andExpect(jsonPath("$[0].actorRole").value("HR_STAFF"))
                .andExpect(jsonPath("$[0].actionType").value("REVOKE_QR"))
                .andExpect(jsonPath("$[0].targetResource").value("EMP-001"))
                .andExpect(jsonPath("$[0].payloadSummary").value("version=2"))
                .andExpect(jsonPath("$[0].status").value("SUCCESS"))
                .andExpect(jsonPath("$[0].previousHash").value("0".repeat(64)))
                .andExpect(jsonPath("$[0].checksum").value("a".repeat(64)));
        mvc.perform(get("/api/v1/qr/audit-logs?limit=201")).andExpect(status().isBadRequest());
    }

    @Test
    void rejectsMalformedBodyAndInvalidFieldsBeforeCallingService() throws Exception {
        for (String content : new String[]{"{", "{\"actorId\":\"bad actor\"}", "{\"actorRole\":\"admin\"}",
                "{\"reason\":\"" + "x".repeat(257) + "\"}"}) {
            mvc.perform(post("/api/v1/qr/EMP-001/revoke").contentType(MediaType.APPLICATION_JSON).content(content))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.correlationId").isNotEmpty());
        }
        mvc.perform(get("/api/v1/qr/" + "x".repeat(37))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/qr/audit-logs?limit=abc")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void legacyGenerateRemainsHonestAndSensitiveToStringsAreRedacted() throws Exception {
        mvc.perform(get("/api/v1/qr/generate")).andExpect(status().isNotImplemented())
                .andExpect(jsonPath("$.code").value("NOT_IMPLEMENTED"));
        assertThat(QrResponseDto.from(QR).toString()).doesNotContain(QR.totpCode(), QR.qrTokenPayload());
        assertThat(WhitelistEmployeeDto.from(EMPLOYEE).toString()).doesNotContain(EMPLOYEE.sharedSecret());
        assertThat(EMPLOYEE.toString()).doesNotContain(EMPLOYEE.sharedSecret());
    }

    @Test
    void transportAndUnexpectedErrorsUseSafeErrorShape() throws Exception {
        mvc.perform(put("/api/v1/qr/EMP-001/revoke")).andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
        mvc.perform(post("/api/v1/qr/EMP-001/revoke").contentType(MediaType.TEXT_PLAIN).content("sensitive input"))
                .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
        mvc.perform(get("/no-such-route")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        when(service.issueQr("EMP-001")).thenThrow(new IllegalStateException("SENSITIVE_INTERNAL_DATA"));
        String body = mvc.perform(get("/api/v1/qr/EMP-001")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty()).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("SENSITIVE_INTERNAL_DATA", "stackTrace");
    }
}
