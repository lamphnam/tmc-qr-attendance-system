package vn.edu.hcmut.tmc.userqr.application.service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import vn.edu.hcmut.tmc.userqr.domain.exception.EmployeeNotFoundException;
import vn.edu.hcmut.tmc.userqr.domain.exception.FactoryNotFoundException;
import vn.edu.hcmut.tmc.userqr.domain.exception.QrRevokedException;
import vn.edu.hcmut.tmc.userqr.domain.exception.QrStorageException;
import vn.edu.hcmut.tmc.userqr.domain.model.AuditEvent;
import vn.edu.hcmut.tmc.userqr.domain.model.AuditLog;
import vn.edu.hcmut.tmc.userqr.domain.model.EmployeeQr;
import vn.edu.hcmut.tmc.userqr.domain.model.QrStatus;
import vn.edu.hcmut.tmc.userqr.domain.model.WhitelistSnapshot;
import vn.edu.hcmut.tmc.userqr.domain.repository.IAuditLogQuery;
import vn.edu.hcmut.tmc.userqr.domain.repository.IAuditLogRepository;
import vn.edu.hcmut.tmc.userqr.domain.repository.IEmployeeQrRepository;
import vn.edu.hcmut.tmc.userqr.domain.service.TotpCodeGenerator;

@Service
public class UserQrApplicationService {
    private final IEmployeeQrRepository employees;
    private final IAuditLogRepository audit;
    private final IAuditLogQuery auditQuery;
    private final TotpCodeGenerator totp;
    private final Clock clock;
    private final SecureRandom random;
    private final TransactionOperations transactions;

    public UserQrApplicationService(IEmployeeQrRepository employees, IAuditLogRepository audit,
            IAuditLogQuery auditQuery, TotpCodeGenerator totp, Clock clock, SecureRandom random,
            TransactionOperations transactions) {
        this.employees = employees;
        this.audit = audit;
        this.auditQuery = auditQuery;
        this.totp = totp;
        this.clock = clock;
        this.random = random;
        this.transactions = transactions;
    }

    public QrIssue issueQr(String employeeId) {
        return issue(employees.findById(employeeId).orElseThrow(EmployeeNotFoundException::new));
    }

    public QrIssue rotateSecret(String employeeId, String actorId, String actorRole, String reason) {
        return transaction(() -> issue(change(employeeId, "ROTATE_QR_SECRET", QrStatus.ACTIVE,
                true, actorId, actorRole, reason)));
    }

    public EmployeeQr revokeQr(String employeeId, String actorId, String actorRole, String reason) {
        return transaction(() -> change(employeeId, "REVOKE_QR", QrStatus.REVOKED,
                false, actorId, actorRole, reason));
    }

    public QrIssue activateQr(String employeeId, String actorId, String actorRole, String reason) {
        return transaction(() -> issue(change(employeeId, "ACTIVATE_QR", QrStatus.ACTIVE,
                true, actorId, actorRole, reason)));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public WhitelistSnapshot exportWhitelist(String factoryId) {
        long version = employees.findWhitelistVersion(factoryId).orElseThrow(FactoryNotFoundException::new);
        return new WhitelistSnapshot(version, employees.findWhitelist(factoryId));
    }

    public EmployeeQr addEmployee(EmployeeQr employee, String actorId, String actorRole, String reason) {
        return transaction(() -> {
            employees.insert(employee);
            recordChange(employee.employeeId(), "ADD_EMPLOYEE", "factoryId=" + employee.factoryId(), actorId, actorRole, reason);
            return employee;
        });
    }

    public void deleteEmployee(String employeeId, String actorId, String actorRole, String reason) {
        transaction(() -> {
            EmployeeQr before = employees.findByIdForUpdate(employeeId).orElseThrow(EmployeeNotFoundException::new);
            employees.delete(employeeId);
            recordChange(employeeId, "DELETE_EMPLOYEE", "factoryId=" + before.factoryId(), actorId, actorRole, reason);
            return null;
        });
    }

    public EmployeeQr transferEmployee(String employeeId, String factoryId, String actorId, String actorRole, String reason) {
        return transaction(() -> {
            EmployeeQr before = employees.findByIdForUpdate(employeeId).orElseThrow(EmployeeNotFoundException::new);
            if (before.factoryId().equals(factoryId)) return before;
            EmployeeQr after = new EmployeeQr(before.employeeId(), before.fullName(), factoryId, before.department(),
                    before.role(), before.sharedSecret(), before.qrStatus(), before.secretVersion(),
                    clock.instant().truncatedTo(ChronoUnit.MILLIS));
            employees.updateQr(after);
            recordChange(employeeId, "TRANSFER_EMPLOYEE", "factoryId=" + before.factoryId() + "->" + factoryId,
                    actorId, actorRole, reason);
            return after;
        });
    }

    public List<AuditLog> auditLogs(int limit) { return auditQuery.findRecent(limit); }

    private EmployeeQr change(String id, String action, QrStatus status, boolean replaceSecret,
                              String actorId, String actorRole, String reason) {
        EmployeeQr before = employees.findByIdForUpdate(id).orElseThrow(EmployeeNotFoundException::new);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        EmployeeQr after = before.changeQr(replaceSecret ? newSecret() : before.sharedSecret(), status, now);
        employees.updateQr(after);
        recordChange(id, action, "secretVersion=" + before.secretVersion() + "->" + after.secretVersion(),
                actorId, actorRole, reason);
        return after;
    }

    private void recordChange(String id, String action, String summary, String actorId, String actorRole, String reason) {
        // Actor fields are untrusted demo metadata until JWT/RBAC is implemented (B21).
        audit.append(new AuditEvent(UUID.randomUUID().toString(), clock.instant().truncatedTo(ChronoUnit.MILLIS),
                defaultValue(actorId, "DEV_OPERATOR"), defaultValue(actorRole, "UNAUTHENTICATED_DEV"),
                action, id, summary
                        + (reason == null || reason.isBlank() ? "" : "; reason=" + reason), "SUCCESS"));
    }

    private QrIssue issue(EmployeeQr employee) {
        if (employee.qrStatus() != QrStatus.ACTIVE) throw new QrRevokedException();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        long step = now.getEpochSecond() / TotpCodeGenerator.TIME_STEP_SECONDS;
        Instant expiry = Instant.ofEpochSecond((step + 1) * TotpCodeGenerator.TIME_STEP_SECONDS);
        String code = totp.generateTotp(employee.sharedSecret(), now);
        return new QrIssue(employee.employeeId(), employee.fullName(), employee.factoryId(), employee.qrStatus(),
                code, step, now, expiry, expiry.getEpochSecond() - now.getEpochSecond(), employee.secretVersion(),
                employee.employeeId() + ":" + code + ":" + employee.secretVersion());
    }

    private String newSecret() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String defaultValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private <T> T transaction(Supplier<T> work) {
        try { return transactions.execute(status -> work.get()); }
        catch (TransactionException e) { throw new QrStorageException(e); }
    }
}
