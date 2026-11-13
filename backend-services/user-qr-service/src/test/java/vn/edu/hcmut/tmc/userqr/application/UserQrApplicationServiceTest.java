package vn.edu.hcmut.tmc.userqr.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.support.TransactionOperations;
import vn.edu.hcmut.tmc.userqr.application.service.UserQrApplicationService;
import vn.edu.hcmut.tmc.userqr.domain.exception.*;
import vn.edu.hcmut.tmc.userqr.domain.model.*;
import vn.edu.hcmut.tmc.userqr.domain.repository.*;
import vn.edu.hcmut.tmc.userqr.domain.service.TotpCodeGenerator;

@ExtendWith(MockitoExtension.class)
class UserQrApplicationServiceTest {
    @Mock IEmployeeQrRepository employees;
    @Mock IAuditLogRepository audit;
    @Mock IAuditLogQuery auditQuery;
    private final TotpCodeGenerator generator = new TotpCodeGenerator();
    private final Instant now = Instant.parse("2026-11-14T00:00:29.999Z");
    private final EmployeeQr initial = new EmployeeQr("EMP-001", "Demo", "BD", "Assembly", "EMPLOYEE",
            "TMC_SECRET_EMP001_V1", QrStatus.ACTIVE, 1, now);
    private UserQrApplicationService service;

    @BeforeEach
    void setUp() {
        SecureRandom random = new SecureRandom() {
            @Override public void nextBytes(byte[] bytes) { Arrays.fill(bytes, (byte) 42); }
        };
        service = new UserQrApplicationService(employees, audit, auditQuery, generator,
                Clock.fixed(now, ZoneOffset.UTC), random, TransactionOperations.withoutTransaction());
    }

    @Test
    void issuesQrWithNextBoundaryExpiryAndNoSecretInOutput() {
        when(employees.findById("EMP-001")).thenReturn(Optional.of(initial));
        var result = service.issueQr("EMP-001");
        assertThat(result.totpCode()).isEqualTo(generator.generateTotp(initial.sharedSecret(), now));
        assertThat(result.expiresAt()).isEqualTo(Instant.parse("2026-11-14T00:00:30Z"));
        assertThat(result.remainingSeconds()).isEqualTo(1);
        assertThat(result.qrTokenPayload()).isEqualTo("EMP-001:" + result.totpCode() + ":1");
        assertThat(result.toString()).doesNotContain(result.totpCode(), result.qrTokenPayload(), initial.sharedSecret());
        verifyNoInteractions(audit);
    }

    @Test
    void rotationChangesSecretIncrementsVersionAndAuditsWithoutSecretMaterial() {
        when(employees.findByIdForUpdate("EMP-001")).thenReturn(Optional.of(initial));
        var result = service.rotateSecret("EMP-001", "hr-demo", "HR_STAFF", "refresh");
        var changed = ArgumentCaptor.forClass(EmployeeQr.class);
        verify(employees).updateQr(changed.capture());
        EmployeeQr stored = changed.getValue();
        assertThat(stored.sharedSecret()).isNotEqualTo(initial.sharedSecret()).hasSize(43);
        assertThat(stored.secretVersion()).isEqualTo(2);
        assertThat(result.secretVersion()).isEqualTo(2);
        assertThat(generator.verifyTotpWithWindow(stored.sharedSecret(),
                generator.generateTotp(initial.sharedSecret(), now), now)).isFalse();
        var event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).append(event.capture());
        assertThat(event.getValue().actionType()).isEqualTo("ROTATE_QR_SECRET");
        assertThat(event.getValue().payloadSummary()).contains("secretVersion=1->2")
                .doesNotContain(initial.sharedSecret(), stored.sharedSecret(), result.totpCode());
    }

    @Test
    void revokedEmployeeCannotIssueThenActivationUsesFreshSecret() {
        AtomicReference<EmployeeQr> row = new AtomicReference<>(initial);
        when(employees.findByIdForUpdate("EMP-001")).thenAnswer(call -> Optional.of(row.get()));
        when(employees.findById("EMP-001")).thenAnswer(call -> Optional.of(row.get()));
        doAnswer(call -> { row.set(call.getArgument(0)); return null; }).when(employees).updateQr(any());
        service.revokeQr("EMP-001", null, null, "left company");
        assertThat(row.get().qrStatus()).isEqualTo(QrStatus.REVOKED);
        assertThat(row.get().secretVersion()).isEqualTo(2);
        assertThatThrownBy(() -> service.issueQr("EMP-001")).isInstanceOf(QrRevokedException.class);
        var reactivated = service.activateQr("EMP-001", null, null, null);
        assertThat(reactivated.qrStatus()).isEqualTo(QrStatus.ACTIVE);
        assertThat(reactivated.secretVersion()).isEqualTo(3);
        assertThat(row.get().sharedSecret()).isNotEqualTo(initial.sharedSecret());
        var events = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit, times(2)).append(events.capture());
        assertThat(events.getAllValues()).extracting(AuditEvent::actionType).containsExactly("REVOKE_QR", "ACTIVATE_QR");
    }

    @Test
    void missingEmployeeDoesNotWriteOrAudit() {
        when(employees.findById("missing")).thenReturn(Optional.empty());
        when(employees.findByIdForUpdate("missing")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.issueQr("missing")).isInstanceOf(EmployeeNotFoundException.class);
        assertThatThrownBy(() -> service.rotateSecret("missing", null, null, null)).isInstanceOf(EmployeeNotFoundException.class);
        verify(employees, never()).updateQr(any());
        verifyNoInteractions(audit);
    }

    @Test
    void exportsAllStatusesAndUsesReadOnlyAuditCapability() {
        when(employees.findWhitelist("BD")).thenReturn(List.of(initial));
        when(auditQuery.findRecent(10)).thenReturn(List.of());
        assertThat(service.exportWhitelist("BD")).containsExactly(initial);
        assertThat(service.auditLogs(10)).isEmpty();
        verifyNoInteractions(audit);
    }

    @Test
    void auditFailureIsPropagatedInsteadOfReturningSuccess() {
        when(employees.findByIdForUpdate("EMP-001")).thenReturn(Optional.of(initial));
        when(audit.append(any())).thenThrow(new QrStorageException(new RuntimeException("unavailable")));
        assertThatThrownBy(() -> service.revokeQr("EMP-001", null, null, null)).isInstanceOf(QrStorageException.class);
        // The real JDBC integration test checks rollback, which this transaction-free unit test cannot prove.
    }

    @Test
    void transactionOpenFailureIsAStorageFailure() {
        TransactionOperations transactions = mock(TransactionOperations.class);
        when(transactions.execute(any())).thenThrow(new CannotCreateTransactionException("DB down"));
        var unavailable = new UserQrApplicationService(employees, audit, auditQuery, generator,
                Clock.fixed(now, ZoneOffset.UTC), new SecureRandom(), transactions);
        assertThatThrownBy(() -> unavailable.rotateSecret("EMP-001", null, null, null)).isInstanceOf(QrStorageException.class);
        verifyNoInteractions(employees, audit);
    }
}
