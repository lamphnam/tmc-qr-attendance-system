package vn.edu.hcmut.tmc.kiosk.application.usecase;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;

import vn.edu.hcmut.tmc.kiosk.domain.model.Direction;
import vn.edu.hcmut.tmc.kiosk.domain.model.GateCommandResult;
import vn.edu.hcmut.tmc.kiosk.domain.model.KioskIdentity;
import vn.edu.hcmut.tmc.kiosk.domain.model.LocalAttendanceEvent;
import vn.edu.hcmut.tmc.kiosk.domain.model.ScanResult;
import vn.edu.hcmut.tmc.kiosk.domain.port.IAttendanceLocalRepository;
import vn.edu.hcmut.tmc.kiosk.domain.port.ITurnstileAdapter;

/**
 * DEV-ONLY slice of ADR-01: it has NO QR / TOTP / whitelist check, so it must never be mistaken for the real scan
 * flow ({@code ProcessQrScanUseCase}, owner: Khanh). What it does prove is the ordering guarantee:
 * <ol>
 *   <li>the event is committed to SQLite as PENDING first, with its stable id;</li>
 *   <li>only then is the open-gate command sent over the local network;</li>
 *   <li>the command outcome is recorded separately from the central sync status.</li>
 * </ol>
 */
@Service
public class QueueSyntheticEventUseCase {

    private final IAttendanceLocalRepository repository;
    private final ITurnstileAdapter turnstile;
    private final KioskIdentity identity;
    private final Clock clock;

    public QueueSyntheticEventUseCase(IAttendanceLocalRepository repository, ITurnstileAdapter turnstile,
                                      KioskIdentity identity, Clock clock) {
        this.repository = repository;
        this.turnstile = turnstile;
        this.identity = identity;
        this.clock = clock;
    }

    static final String DEFAULT_EMPLOYEE_ID = "EMP001";

    /** Any null argument falls back to a default: demo employee, this kiosk's default gate, direction IN. */
    public LocalAttendanceEvent execute(String employeeId, String gateId, Direction direction) {
        String employee = employeeId == null || employeeId.isBlank() ? DEFAULT_EMPLOYEE_ID : employeeId.trim();
        String gate = gateId == null || gateId.isBlank() ? identity.defaultGateId() : gateId.trim();
        Direction dir = direction == null ? Direction.IN : direction;

        Instant now = clock.instant();
        LocalAttendanceEvent pending = LocalAttendanceEvent.newPending(UUID.randomUUID(), employee,
                identity.factoryId(), gate, dir, now, ScanResult.ALLOWED, true, now);

        repository.insertPending(pending);

        GateCommandResult command = turnstile.openGate(gate);
        repository.updateGateCommandStatus(pending.attendanceId(), command.status(), command.detail());

        return repository.findById(pending.attendanceId()).orElseThrow();
    }
}
